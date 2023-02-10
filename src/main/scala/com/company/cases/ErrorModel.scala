package com.company.cases

import caliban.CalibanError.ExecutionError
import caliban.schema.Schema
import cats.Semigroup
import cats.data.Validated
import cats.syntax.semigroup._
import zio.IO

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.Try

object ErrorModel {

  implicit val combineStringErrors: Semigroup[String] =
    Semigroup.instance[String] {
      (a, b) => a + " | " + b
    }

  implicit def customEffectSchema[A](implicit s: Schema[Any, A]): Schema[Any, IO[String, A]] =
    Schema.customErrorEffectSchema((message: String) => ExecutionError(message))

  type InputValidation[T] = Validated[String, T]

  private def nameValidation(name: String): InputValidation[String] =
    Validated.cond(name.nonEmpty, name, "Name must not be blank.")
      .combine(Validated.cond(name.length < 100, name, "Name must be less than 100 characters."))

  private def dateValidation(date: Option[String]): InputValidation[String] =
    if (date.isEmpty) Validated.valid("")
    else Validated
      .fromTry(Try(LocalDate.parse(date.get).toString))
      .leftMap(e =>
        s"${e.getMessage} -> ISO 8601 standard date format yyyy-MM-dd required."
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
