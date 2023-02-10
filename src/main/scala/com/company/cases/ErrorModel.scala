package com.company.cases

import cats.Semigroup
import cats.data.Validated
import cats.implicits._

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.Try

object ErrorModel {

  import cats.implicits._

  implicit val combineStringErrors: Semigroup[IllegalArgumentException] =
    Semigroup.instance[IllegalArgumentException] {
      (a, b) => new IllegalArgumentException(a.getMessage + " | " + b.getMessage)
    }

  type InputValidation[T] = Validated[IllegalArgumentException, T]

  private def nameValidation(name: String): InputValidation[String] =
    Validated.cond(name.nonEmpty, name, new IllegalArgumentException("Name must not be blank."))
      .combine(Validated.cond(name.length < 100, name, new IllegalArgumentException("Name must be less than 100 characters.")))

  private def dateValidation(date: Option[String]): InputValidation[String] =
    if (date.isEmpty) Validated.valid("")
    else Validated
      .fromTry(Try(LocalDate.parse(date.get).toString))
      .leftMap(e =>
        new IllegalArgumentException(s"${e.getMessage} -> ISO 8601 standard date format yyyy-MM-dd required.")
      )

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
