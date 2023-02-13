package com.company.cases

import RequestError._

import cats.data.Validated
import cats.syntax.semigroup._
import zio.{IO, UIO, ZIO}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.Try


sealed trait Validation
object Validation {
  /*
    ZIO parallel combinators automatically run effects on separate Fibers, including handling interruptions.
   */
  type ArgumentValidation[T] = UIO[Validated[InputValidationError, T]]
  type InputValidation[T] = IO[InputValidationError, T]

  private case object NonEmptyString extends Validation
  private case object StringLength extends Validation

  private def stringValidation(value: String, argName: String, validation: Validation): ArgumentValidation[Unit] =
    ZIO.succeed {
      validation match {
        case NonEmptyString => Validated.cond(
          value.nonEmpty,
          (),
          InputValidationError(s"$argName must not be blank.")
        )
        case StringLength => Validated.cond(
          value.length < 100,
          (),
          InputValidationError(s"$argName must be less than 100 characters.")
        )
      }
    }

  private def dateValidation(date: Option[String], argName: String): ArgumentValidation[Unit] =
    ZIO.succeed {
      if (date.isEmpty) Validated.valid(())
      else Validated
        .fromTry(Try(LocalDate.parse(date.get).toString))
        .bimap(e =>
          InputValidationError(
            s"${e.getMessage} -> ISO-8601 standard date format yyyy-MM-dd required for $argName."
          ),
          _ => ()
        )
    }

  private def instantValidation(instant: Option[String], argName: String): ArgumentValidation[Unit] =
    ZIO.succeed {
      if (instant.isEmpty) Validated.valid(())
      else Validated
        .fromTry(Try(Instant.parse(instant.get).toString))
        .bimap(e =>
          InputValidationError(
            s"""
               |${e.getMessage} -> ISO-8601 format yyyy-MM-ddTHH:mm:ss.SSSZ required for $argName:
               |
               |THH:mm:ss.SSS represents the time of day, with hours, minutes, seconds, and fractional seconds.
               |Z represents the time zone offset, either Z for UTC, or ±HH:mm for an offset in hours and minutes.
               |
               |For example, the string 2022-12-03T10:15:30.000Z represents the Instant corresponding to December 3rd, 2022 at 10:15:30 AM UTC.
               |For PST, the format is 2022-12-03T10:15:30.000-08:00 (same approach for other dates based on UTC offset).
               |""".stripMargin
          ),
          _ => ()
        )
    }

  implicit class ValidateListCases(args: ListCases) {
    def validate: InputValidation[Unit] =
      for {
        validated <- instantValidation(args.created, "Created")
        _ <- ZIO.fromEither(validated.toEither)
      } yield ()
  }

  implicit class ValidateCreateCase(args: CreateCase) {
    private def validationEffects: Vector[ArgumentValidation[Unit]] =
      Vector(
        stringValidation(args.name, "Name", NonEmptyString),
        stringValidation(args.name, "Name", StringLength),
        dateValidation(Some(args.dateOfBirth), "DateOfBirth"),
        dateValidation(args.dateOfDeath, "DateOfDeath")
      )

    private def createCase: Case =
      Case(
        UUID.randomUUID,
        args.name,
        LocalDate.parse(args.dateOfBirth),
        if (args.dateOfDeath.nonEmpty) Some(LocalDate.parse(args.dateOfDeath.get)) else Option.empty[LocalDate],
        CaseStatus.Pending, // a new Case starts Pending
        Instant.now,
        Instant.now
      )

    def validate: InputValidation[Case] =
      for {
        validated <- ZIO.reduceAllPar(
          ZIO.succeed(Validated.valid(())),
          validationEffects
        )(_ |+| _)
        newCase <- ZIO.fromEither(
          validated.map(_ =>
            createCase
          ).toEither
        )
      } yield newCase
  }
}
