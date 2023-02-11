package com.company.cases

import ErrorModel._

import cats.data.Validated
import cats.syntax.semigroup._

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.Try


object Validation {
  type InputValidation[T] = Validated[InputValidationError, T]

  private def nameValidation(name: String): InputValidation[String] =
    Validated.cond(name.nonEmpty, name, InputValidationError("Name must not be blank."))
      .combine(Validated.cond(name.length < 100, name, InputValidationError("Name must be less than 100 characters.")))

  private def dateValidation(date: Option[String]): InputValidation[String] =
    if (date.isEmpty) Validated.valid("")
    else Validated
      .fromTry(Try(LocalDate.parse(date.get).toString))
      .leftMap(e =>
        InputValidationError(
          s"${e.getMessage} -> ISO-8601 standard date format yyyy-MM-dd required."
        )
      )

  private def instantValidation(instant: Option[String]): InputValidation[String] =
    if (instant.isEmpty) Validated.valid("")
    else Validated
      .fromTry(Try(Instant.parse(instant.get).toString))
      .leftMap(e =>
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
        )
      )

  def validateListCases(args: ListCases): InputValidation[Unit] =
    instantValidation(args.created).map(_ => ())

  def validateCreateCase(args: CreateCase): InputValidation[Case] =
    (nameValidation(args.name) |+| dateValidation(Some(args.dateOfBirth)) |+| dateValidation(args.dateOfDeath))
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
}
