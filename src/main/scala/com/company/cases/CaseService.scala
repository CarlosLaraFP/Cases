package com.company.cases

import Validation._
import TableAction._
import caliban.RootResolver
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import doobie.postgres._
import doobie.postgres.implicits._
import doobie.util.fragment.Fragment
import zio._
import zio.stream.ZStream
// provides the necessary implicit conversion from doobie.Transactor to zio.Task
import zio.interop.catz._
import zio.interop.catz.implicits._
import java.time._


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
  private def create(databaseService: DatabaseService, externalService: ExternalService): CaseService =
    new CaseService(databaseService, externalService)

  val live: ZLayer[DatabaseService with ExternalService, Throwable, CaseService] =
    ZLayer.fromFunction(create _)
}

class DatabaseService(connection: PostgresConnection, hub: Hub[CaseStatusChanged]) {
  // Using Doobie with ZIO Cats Effect 3 interop to interact with PostgreSQL
  // doobie.postgres.implicits._ for automatic casts
  def modifyTable(args: ModifyTable): Result[Mutation] = {
    val postgresQuery = args.action match {
      case Create =>
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
        """
      case Delete =>
        sql"""
          DROP TABLE "Case";
        """
      case Clear =>
        sql"""
          DELETE FROM "Case";
        """
    }
    connection
      .executeMutation(postgresQuery)
      .map(_ =>
        Mutation(s"${args.action} successful", None, None)
      )
  }

  def createCase(args: CreateCase): Result[Mutation] =
    for {
      newCase <- ZIO.fromEither(validateCreateCase(args).toEither)
      _ <- connection
        .executeMutation(
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
            """
        )
    } yield Mutation(
      s"Case ${newCase.name} inserted successfully.",
      Some(newCase.id.toString),
      Some(newCase.status)
    )

  def listCases(args: ListCases): Result[List[Case]] =
    // TODO: functional streaming library fs2 to use Stream instead of List to avoid potential OOM runtime exception
    for {
      _ <- ZIO.fromEither(validateListCases(args).toEither)
      cases <- connection
        .executeQuery(
          sql"""
          SELECT id, name, dateOfBirth, dateOfDeath, status, created, statusChange
          FROM "Case"
          WHERE status = ${args.status.toString}
        """
        )
    } yield cases

  def updateCase(args: UpdateCase): Result[Mutation] =
    for {
      _ <- connection
        .executeMutation(
          sql"""
            UPDATE "Case"
            SET
              status = ${args.status.toString},
              statusChange = ${Instant.now}
            WHERE id = ${args.id};
          """
        )
      _ <- hub
        .publish(CaseStatusChanged(args.id, args.status))
        .forkDaemon
    } yield Mutation(
      s"Status ${args.status} applied successfully.",
      Some(args.id.toString),
      Some(args.status)
    )

  def deleteCase(args: DeleteCase): Result[Mutation] =
    connection
      .executeMutation(
        sql"""
          DELETE FROM "Case"
          WHERE id = ${args.id};
        """
      )
      .map(_ =>
        Mutation(s"Case deleted successfully.", Some(args.id.toString), None)
      )
}
object DatabaseService {
  private def create(config: PostgresConnection, hub: Hub[CaseStatusChanged]): DatabaseService =
    new DatabaseService(config, hub)

  val live: ZLayer[PostgresConnection with Hub[CaseStatusChanged], Throwable, DatabaseService] =
    ZLayer.fromFunction(create _)
}

case class PostgresConnection(transactor: Aux[Task, Unit]) {
  def executeMutation(fragment: Fragment): Result[Int] =
    transactor
      .trans
      .apply(
        fragment
          .update
          .run
      )
      .mapError(e => InputValidationError(e.getMessage))

  def executeQuery(fragment: Fragment): Result[List[Case]] =
    transactor
      .trans
      .apply(
        fragment
          .query[Case]
          .to[List]
      )
      .mapError(e => InputValidationError(e.getMessage))
}
object PostgresConnection {
  private def create(driver: String, url: String, user: String, password: String): PostgresConnection =
    PostgresConnection(
      Transactor.fromDriverManager[Task](
        driver, // driver classname
        url, // JDBC URL
        user, // username
        password // password
      )
    )

  def live(driver: String, url: String, user: String, password: String): ZLayer[Any, Nothing, PostgresConnection] =
    ZLayer.succeed(create(driver, url, user, password))
}

class ExternalService(retryAttemptsLimit: Int, hub: Hub[CaseStatusChanged]) {
  /*
    Mocking SQS FIFO queue: In this legal case management domain, case status changes cannot arrive out of order.
    Therefore, the flaky nature of the service could be attributed to SendMessage API throttling,
    or OverLimit exception from in-flight messages if the consumer microservice is not deleting them fast enough.
   */
  val caseStatusChanged: ZStream[Any, Throwable, CaseStatusChanged] =
    ZStream
      .fromHub(hub)
      .tap(publishMessage)

  // Testing a side-effect in the server, but the ZStream events are meant for GraphQL client delivery (through WebSocket)
  def publishMessage(caseStatusChanged: CaseStatusChanged): Task[String] =
    Random
      .nextBoolean
      .flatMap { flag =>
        if (flag) ZIO.succeed {
          val result = s"Case ${caseStatusChanged.id.toString} status changed to ${caseStatusChanged.status.toString}"
          println(result)
          result
        }
        else ZIO.fail {
          val error = "Unable to reach external service"
          println(error)
          new RuntimeException(error)
        }
      }
      .retry {
        val primarySchedule = Schedule.recurs(retryAttemptsLimit) && Schedule.exponential(1.second, 2.0)
        //val secondarySchedule = Schedule.recurs(3) && Schedule.fibonacci(1.minute)
        val elapsedTime = Schedule.elapsed.map(duration => println(s"Total time elapsed: ${duration.toMillis} milliseconds."))
        //(primarySchedule ++ secondarySchedule) >>> elapsedTime
        primarySchedule.jittered(0.0, 1.0) >>> elapsedTime
      }
}
object ExternalService {
  private def create(retryAttemptsLimit: Int)(hub: Hub[CaseStatusChanged]): ExternalService =
    new ExternalService(retryAttemptsLimit, hub)

  def live(retryAttemptsLimit: Int): ZLayer[Hub[CaseStatusChanged], Throwable, ExternalService] =
    ZLayer.fromFunction(create(retryAttemptsLimit)(_))
}

/*
  TODO: Schedules, combinators (&&), and sequencing (++) for retry logic
    - once
    - recurs(Int) - retries n times and returns the first success or the last failure
    - spaced(Duration) - retries every n.seconds until a success is returned
    - exponential backoff
    - Fibonacci - 1s, 1s, 2s, 3s, 5s, 8s, ...
 */
