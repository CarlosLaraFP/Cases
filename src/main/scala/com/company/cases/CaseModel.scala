package com.company.cases

import caliban.CalibanError.ExecutionError
import caliban.schema.Schema
import doobie.util.{Get, Put, Read, Write}
import doobie.implicits._
import doobie.postgres._
import doobie.postgres.implicits._
import zio.IO
import cats.{Monoid, Semigroup}

import java.time.{Instant, LocalDate}
import java.util.UUID

/*
  When working with GraphQL, we need to define a schema, which defines what kind of data with which types we can query.
  In GraphQL, schemas are defined in their type system.
  Here, a Case has 7 fields and the status field is a sealed trait, which means it can only be one of 4 values.

  Instant is used for working with time-based calculations.
  LocalDate is used for working with human-readable date values.

  For a legal case management service, there are several options for the primary key (case ID) of new records, each with their own benefits and trade-offs:
  Auto-generated integers: These are the simplest and most straightforward IDs to generate and manage, as they can be automatically incremented for each new case. However, they can potentially reveal information about the number of cases in the system, and if the data is leaked, the IDs can be easily guessed and used for malicious purposes.
  UUIDs: Universal Unique Identifiers (UUIDs) are 128-bit values that are generated to be unique across space and time. They are suitable for use as primary keys, as they are difficult to guess or generate in a malicious manner. They are also immune to replication problems, as they can be generated independently on each node.
  Sequential UUIDs: Sequential UUIDs are UUIDs that are generated in a way that preserves their sorting order, making them suitable for use as primary keys. This can improve the performance of some types of queries and indexes.
  Ultimately, the choice of primary key depends on the specific requirements and constraints of the legal case management service.
  In general, UUIDs or sequential UUIDs are a safe choice, as they provide a high level of uniqueness and security.
 */
final case class Case(
  id: UUID,
  name: String,
  dateOfBirth: LocalDate,
  dateOfDeath: Option[LocalDate],
  status: CaseStatus,
  created: Instant,
  statusChange: Instant
)
object Case {
  // field not natively supported by Doobie
  implicit val uuidGet: Get[UUID] = Get[String].map(UUID.fromString)
  implicit val uuidPut: Put[UUID] = Put[String].contramap(_.toString)
  // field not natively supported by Doobie
  implicit val caseStatusGet: Get[CaseStatus] =
    Get[String].map {
      case "Pending" => CaseStatus.Pending
      case "UnderReview" => CaseStatus.UnderReview
      case "Deficient" => CaseStatus.Deficient
      case "Submitted" => CaseStatus.Submitted
    }
  implicit val caseStatusPut: Put[CaseStatus] =
    Put[String].contramap {
      case CaseStatus.Pending => "Pending"
      case CaseStatus.UnderReview => "UnderReview"
      case CaseStatus.Deficient => "Deficient"
      case CaseStatus.Submitted => "Submitted"
    }
  implicit val caseRead: Read[Case] =
    Read[(UUID, String, LocalDate, Option[LocalDate], CaseStatus, Instant, Instant)].map(
      db => Case(db._1, db._2, db._3, db._4, db._5, db._6, db._7)
    )
  // this does not seem necessary
  implicit val caseWrite: Write[Case] =
    Write[(UUID, String, LocalDate, Option[LocalDate], CaseStatus, Instant, Instant)].contramap(
      c => (c.id, c.name, c.dateOfBirth, c.dateOfDeath, c.status, c.created, c.statusChange)
    )
}
sealed trait CaseStatus
object CaseStatus {
  case object Pending extends CaseStatus
  case object UnderReview extends CaseStatus
  case object Deficient extends CaseStatus
  case object Submitted extends CaseStatus
}

sealed trait RequestError
object RequestError {
  case class PostgresError(message: String, sql: String) extends RequestError
  case class InputValidationError(message: String) extends RequestError
  object InputValidationError {
    implicit val validationMonoid: Monoid[InputValidationError] =
      Monoid.instance[InputValidationError](
        InputValidationError(""),
        (errorA, errorB) =>
          InputValidationError(
            errorA.message + " | " + errorB.message
          )
      )
  }
  type Result[T] = IO[RequestError, T]
  type CustomSchema[T] = Schema[Any, T]
  // required by Caliban to replace Throwable error channel
  implicit def customEffectSchema[A : CustomSchema]: CustomSchema[Result[A]] =
    // requires an implicit Schema[Any, A] in scope
    Schema.customErrorEffectSchema {
      case InputValidationError(message) => ExecutionError(message)
      case PostgresError(message, sql) =>
        ExecutionError(
          s"$message from SQL: $sql"
            .replaceAll("\\s+", " ")
            .trim
        )
    }
}
