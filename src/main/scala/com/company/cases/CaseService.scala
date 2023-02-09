package com.company.cases

import caliban.RootResolver
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import doobie.postgres._
import doobie.postgres.implicits._
import zio._
import zio.stream.ZStream
// provides the necessary implicit conversion from doobie.Transactor to zio.Task
import zio.interop.catz._
import zio.interop.catz.implicits._
import java.time._
import java.util.UUID


class CaseService(databaseService: DatabaseService, externalService: ExternalService) {
  def rootResolver: UIO[RootResolver[Queries, Mutations, Subscriptions]] =
    ZIO.succeed {
      RootResolver(
        Queries(
          databaseService.listCases
        ),
        Mutations(
          databaseService.modifyTable,
          databaseService.createCase,
          databaseService.updateCase,
          databaseService.deleteCase
        ),
        Subscriptions(
          externalService.caseStatusChanged
        )
      )
    }
}
object CaseService {
  def create(databaseService: DatabaseService, externalService: ExternalService): CaseService =
    new CaseService(databaseService, externalService)

  val live: ZLayer[DatabaseService with ExternalService, Throwable, CaseService] =
    ZLayer.fromFunction(create _)
}

class DatabaseService(dbConfig: PostgresConfig, hub: Hub[CaseStatusChanged]) {
  // Using Doobie with ZIO Cats Effect 3 interop to interact with PostgreSQL
  lazy val connection: Aux[Task, Unit] =
    Transactor.fromDriverManager[Task](
      dbConfig.driver, // driver classname
      dbConfig.url, // JDBC URL
      dbConfig.user, // username
      dbConfig.password // password
    )

  def modifyTable(modifyTable: ModifyTable): Task[MutationResult] = {
    val result = modifyTable.action match {
      case TableAction.Create =>
        connection
          .trans
          .apply(
            sql"""
            CREATE TABLE "Case" (
              id UUID NOT NULL,
              name TEXT NOT NULL,
              dateOfBirth DATE NOT NULL,
              dateOfDeath DATE,
              status TEXT NOT NULL,
              created TIMESTAMP NOT NULL,
              statusChange TIMESTAMP NOT NULL,
              PRIMARY KEY (id)
            );
          """.update.run)

      case TableAction.Delete =>
        connection
          .trans
          .apply(
            sql"""
             DROP TABLE "Case";
            """.update.run
          )

      case TableAction.Clear =>
        connection
          .trans
          .apply(
            sql"""
               DELETE FROM "Case";
              """.update.run
          )
    }
    result
      .map(_ =>
        MutationResult(s"${modifyTable.action} successful", None, None)
      )
      .catchAll(e => ZIO.attempt {
        MutationResult(s"Doobie ${modifyTable.action} table error: ${e.getMessage}", None, None)
      })
  }

  def createCase(args: CreateCase): Task[MutationResult] = {
    val newCase = Case(
      UUID.randomUUID,
      args.name,
      LocalDate.parse(args.dateOfBirth),
      if (args.dateOfDeath.nonEmpty) Some(LocalDate.parse(args.dateOfDeath.get)) else Option.empty[LocalDate],
      CaseStatus.Pending, // a new Case starts Pending
      Instant.now,
      Instant.now
    )
    // doobie.postgres.implicits._ for automatic casts
    connection
      .trans
      .apply(
        sql"""
            INSERT INTO "Case" (id, name, dateOfBirth, dateOfDeath, status, created, statusChange)
            VALUES (
              ${newCase.id},
              ${newCase.name},
              ${newCase.dateOfBirth},
              ${newCase.dateOfDeath},
              ${newCase.status.toString},
              ${newCase.created},
              ${newCase.statusChange}
            );
            """.update.run
      )
      .map(_ =>
        MutationResult(s"Case ${newCase.name} inserted successfully.", Some(newCase.id.toString), None)
      )
      .catchAll(e => ZIO.attempt {
        MutationResult(s"Failure: ${e.getMessage}", None, None)
      })
  }

  // TODO: functional streaming library fs2 to use Stream instead of List to avoid potential OOM runtime exception
  def listCases(args: ListCases): Task[List[Case]] =
    connection
      .trans
      .apply(
        sql"""
          SELECT id, name, dateOfBirth, dateOfDeath, status, created, statusChange
          FROM "Case"
          WHERE status = ${args.status.toString}
        """
          .query[Case]
          .to[List]
      )
      .mapError { e =>
        new Exception(e.getMessage)
      }
      // Caliban's Executor is expecting a Throwable to be returned in order to handle the error

  def updateCase(args: UpdateCase): Task[MutationResult] = {
    // Later: cats.data to build non-monadic string
    val updateEffect = connection
      .trans
      .apply(
        sql"""
          UPDATE "Case"
          SET
            status = ${args.status.toString},
            statusChange = ${Instant.now}
          WHERE id = ${args.id};
          """
          .update.run
      )
      .map(_ =>
        MutationResult(s"Status ${args.status} applied successfully.", Some(args.id.toString), Some(args.status))
      )
    // When a case status changes, an appropriate message should be published by the API to some "external" service.
    for {
      result <- updateEffect
      event = CaseStatusChanged(args.id, args.status)
      _ <- hub
        .publish(event)
        .forkDaemon
    } yield result
  }

  def deleteCase(args: DeleteCase): Task[MutationResult] =
    connection
      .trans
      .apply(
        sql"""
          DELETE FROM "Case"
          WHERE id = ${args.id};
          """
          .update.run
      )
      .map(_ =>
        MutationResult(s"Case deleted successfully.", Some(args.id.toString), None)
      )
      .catchAll(e => ZIO.attempt {
        MutationResult(s"Failure: ${e.getMessage}", Some(args.id.toString), None)
      })
}
object DatabaseService {
  def create(config: PostgresConfig, hub: Hub[CaseStatusChanged]): DatabaseService =
    new DatabaseService(config, hub)

  val live: ZLayer[PostgresConfig with Hub[CaseStatusChanged], Throwable, DatabaseService] =
    ZLayer.fromFunction(create _)
}

case class PostgresConfig(driver: String, url: String, user: String, password: String)
object PostgresConfig {
  def create(driver: String, url: String, user: String, password: String): PostgresConfig =
    PostgresConfig(driver, url, user, password)

  def live(driver: String, url: String, user: String, password: String): ZLayer[Any, Nothing, PostgresConfig] =
    ZLayer.succeed(create(driver, url, user, password))
}

class ExternalService(retryAttemptsLimit: Int, hub: Hub[CaseStatusChanged]) {
  /*
    Mocking SQS FIFO queue: In this legal case management domain, case status changes cannot arrive out of order.
    Therefore, the flaky nature of the service can be attributed to SendMessage API throttling,
    or OverLimit exception from in-flight messages if the consumer microservice is not deleting them fast enough.
   */
  val caseStatusChanged: ZStream[Any, Throwable, CaseStatusChanged] =
    ZStream
      .fromHub(hub)
      .tap(publishMessage)

  def publishMessage(caseStatusChanged: CaseStatusChanged): Task[String] =
    Random
      .nextBoolean
      .flatMap { flag =>
        if (flag) ZIO.succeed {
          s"Case ${caseStatusChanged.id.toString} status changed to ${caseStatusChanged.status.toString}"
        }
        else ZIO.fail {
          new RuntimeException("Unable to reach external service")
        }
      }
      .retry {
        val primarySchedule = Schedule.recurs(retryAttemptsLimit) && Schedule.exponential(1.second, 2.0)
        val secondarySchedule = Schedule.recurs(3) && Schedule.fibonacci(1.minute)
        val elapsedTime = Schedule.elapsed.map(duration => println(s"Total time elapsed: $duration"))
        (primarySchedule ++ secondarySchedule) >>> elapsedTime
      }
}
object ExternalService {
  def create(retryAttemptsLimit: Int)(hub: Hub[CaseStatusChanged]): ExternalService =
    new ExternalService(retryAttemptsLimit, hub)

  def live(retryAttemptsLimit: Int): ZLayer[Hub[CaseStatusChanged], Throwable, ExternalService] =
    ZLayer.fromFunction(create(retryAttemptsLimit)(_))
}

/*
  TODO: Schedules, combinators (&&), and sequencing (++) for retry logiic
    - once
    - recurs(Int) - retries n times and returns the first success or the last failure
    - spaced(Duration) - retries every n.seconds until a success is returned
    - exponential backoff
    - Fibonacci - 1s, 1s, 2s, 3s, 5s, 8s, ...
 */
