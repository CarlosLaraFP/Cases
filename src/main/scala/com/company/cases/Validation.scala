package com.company.cases

import ErrorModel._
import cats.data.Validated
//import cats.syntax.monoid._
import zio.{IO, UIO, ZIO}
import zio.interop.catz._
import cats.implicits._

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.Try

/*
  ZIO parallel combinators automatically run effects on separate Fibers, including handling interruptions.
 */

//sealed trait Validation
object Validation {
  type ArgumentValidation[T] = UIO[Validated[InputValidationError, T]]
  type InputValidation[T] = IO[InputValidationError, T]

  private def nameValidation(name: String): ArgumentValidation[Unit] =
    ZIO.succeed {
      Validated.cond(name.nonEmpty, (), InputValidationError("Name must not be blank."))
        .combine(Validated.cond(name.length < 100, (), InputValidationError("Name must be less than 100 characters.")))
    }

  private def dateValidation(date: Option[String]): ArgumentValidation[Unit] =
    ZIO.succeed {
      if (date.isEmpty) Validated.valid("")
      else Validated
        .fromTry(Try(LocalDate.parse(date.get).toString))
        .bimap(e =>
          InputValidationError(
            s"${e.getMessage} -> ISO-8601 standard date format yyyy-MM-dd required."
          ),
          _ => ()
        )
    }

  private def instantValidation(instant: Option[String]): ArgumentValidation[Unit] =
    ZIO.succeed {
      if (instant.isEmpty) Validated.valid("")
      else Validated
        .fromTry(Try(Instant.parse(instant.get).toString))
        .bimap(e =>
          InputValidationError(
            s"""
               |${e.getMessage} -> ISO-8601 format yyyy-MM-ddTHH:mm:ss.SSSZ required:
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
        validated <- instantValidation(args.created)
        _ <- ZIO.fromEither(validated.toEither)
      } yield ()
  }

  implicit class ValidateCreateCase(args: CreateCase) {
    private def validationEffects: Vector[ArgumentValidation[Unit]] =
      Vector(
        nameValidation(args.name),
        dateValidation(Some(args.dateOfBirth)),
        dateValidation(args.dateOfDeath)
      )

    def validate: InputValidation[Case] =
      for {
        validated <- ZIO
          .reduceAllPar(
            ZIO.succeed(Validated.valid(())),
            validationEffects
          )(_ |+| _)
        either = validated
          .map(_ =>
            Case(
              UUID.randomUUID,
              args.name,
              LocalDate.parse(args.dateOfBirth),
              if (args.dateOfDeath.nonEmpty) Some(LocalDate.parse(args.dateOfDeath.get)) else Option.empty[LocalDate],
              CaseStatus.Pending, // a new Case starts Pending
              Instant.now,
              Instant.now
            )
          )
          .toEither
        newCase <- ZIO.fromEither(either)
      } yield newCase
  }
}
