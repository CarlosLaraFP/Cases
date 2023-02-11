import com.company.cases._
import com.company.cases.ErrorModel._

import zio._
import zio.test.TestAspect._
import zio.test._
import java.util.UUID


object CaseSpec extends ZIOSpecDefault {

  // TODO: Unit tests

  val deleteTableTest: Spec[DatabaseService, RequestError] =
    test("databaseService.modifyTable with Delete") {
      val effect = for {
        dbService <- ZIO.service[DatabaseService]
        mutation = ModifyTable(TableAction.Delete)
        result <- dbService.modifyTable(mutation)
      } yield result.result

      assertZIO(effect) {
        Assertion.assertion("RDS PostgreSQL DB table deleted") {
          _.contains("success")
        }
      }
    }

  val createTableTest: Spec[DatabaseService, RequestError] =
    test("databaseService.modifyTable with Create") {
      val effect = for {
        dbService <- ZIO.service[DatabaseService]
        mutation = ModifyTable(TableAction.Create)
        result <- dbService.modifyTable(mutation)
      } yield result.result

      assertZIO(effect) {
        Assertion.assertion("RDS PostgreSQL DB table created") {
          _.contains("success")
        }
      }
    }

  val clearTableTest: Spec[DatabaseService, RequestError] =
    test("databaseService.modifyTable with Clear") {
      val effect = for {
        dbService <- ZIO.service[DatabaseService]
        mutation = ModifyTable(TableAction.Clear)
        result <- dbService.modifyTable(mutation)
      } yield result.result

      assertZIO(effect) {
        Assertion.assertion("RDS PostgreSQL DB table cleared") {
          _.contains("success")
        }
      }
    }

  val createCaseTest: Spec[DatabaseService, RequestError] =
    test("databaseService.createCase Mutation with Doobie") {
      val effect = for {
        dbService <- ZIO.service[DatabaseService]
        mutation = CreateCase("Bones in D.C.", "1990-11-12", None)
        result <- dbService.createCase(mutation)
      } yield result

      assertZIO(effect) {
        Assertion.assertion("Case record successfully inserted in RDS PostgreSQL DB table") {
          c => c.result.contains("success") && c.caseId.nonEmpty
        }
      }
    }

  val listCasesTest: Spec[DatabaseService, RequestError] =
    test("databaseService.listCases Query with Doobie") {
      val effect = for {
        dbService <- ZIO.service[DatabaseService]
        query = ListCases(CaseStatus.Pending, None)
        cases <- dbService.listCases(query)
      } yield cases

      assertZIO(effect) {
        Assertion.assertion("Case records successfully retrieved from RDS PostgreSQL DB table") {
          cases => cases.size == 1 && cases.head.status == CaseStatus.Pending
        }
      }
    }

  // TODO: Integration tests
  /*
    An integration test is a type of test that checks how multiple parts of a system work together.
    The test below interacts with the DatabaseService, which interacts with a PostgreSQL database
    to perform the main case management operations.
    This test verifies that all of these interactions with the database service are working as expected.
   */
  val caseLifecycleTest: Spec[DatabaseService, RequestError] =
    test("Create, list, update, and delete Case") {
      val effect = for {
        dbService <- ZIO.service[DatabaseService]
        create = CreateCase("Neck Injury from Automobile Accident", "1972-10-27", None)
        created <- dbService.createCase(create)
        list = ListCases(CaseStatus.Pending, None)
        cases <- dbService.listCases(list)
        update = UpdateCase(UUID.fromString(created.caseId.get), CaseStatus.UnderReview)
        updated <- dbService.updateCase(update)
        updatedCases <- dbService.listCases(ListCases(CaseStatus.UnderReview, None))
        delete = DeleteCase(UUID.fromString(created.caseId.get))
        deleted <- dbService.deleteCase(delete)
        results = (created, cases, updated, updatedCases, deleted)
      } yield results

      assertZIO(effect) {
        Assertion.assertion("Verify all database interactions") { r =>
          (r._1.result.contains("success") && r._1.caseId.nonEmpty) &&
            (r._2.size == 1 && r._2.head.status == CaseStatus.Pending) &&
              (r._3.result.contains("success") && r._3.caseId.nonEmpty && r._3.caseId.get == r._1.caseId.get) &&
                (r._4.size == 1 && r._4.head.status == CaseStatus.UnderReview) &&
                  (r._5.result.contains("success") && r._5.caseId.nonEmpty && r._5.caseId.get == r._3.caseId.get)
        }
      }
    }

  val flakyServiceTest: Spec[ExternalService, Throwable] =
    test("Publish message to ExternalService when a Case status changes") {
      val effect = for {
        flakyService <- ZIO.service[ExternalService]
        status = CaseStatusChanged(java.util.UUID.randomUUID, CaseStatus.Deficient)
        result <- flakyService.publishMessage(status)
      } yield result

      assertZIO(effect) {
        Assertion.containsString("status changed")
      }
    }

  // TODO: Property-based tests

  val pbtCreateCase: Spec[DatabaseService, RequestError] =
    test("PBT: databaseService.createCase") {

      val nameGenerator = Gen.stringBounded(1, 99)(Gen.char)

      val dobGenerator = for {
        year <- Gen.stringN(4)(Gen.numericChar)
        month <- Gen.fromIterable(1 to 12).map(i => if (i < 10) s"0$i" else s"$i")
        day <- Gen.fromIterable(1 to 28).map(i => if (i < 10) s"0$i" else s"$i")
      } yield s"$year-$month-$day"

      val dodGenerator = Gen.option(dobGenerator)

      check(nameGenerator, dobGenerator, dodGenerator) { (name, dob, dod) =>
        val effect = for {
          dbService <- ZIO.service[DatabaseService]
          create = CreateCase(name, dob, dod)
          result <- dbService.createCase(create)
        } yield result

        effect.map(m =>
          assertTrue(
            m.result.nonEmpty &&
              m.caseId.nonEmpty &&
              m.caseStatus.get == CaseStatus.Pending
          )
        )
      }
    }

  // TODO: Failure test cases

  val invalidDateFailure: Spec[DatabaseService, RequestError] =
    test("Date does not exist in Calendar should fail") {
      val effect = for {
        dbService <- ZIO.service[DatabaseService]
        create = CreateCase("Case from another universe", "2050-11-34", None)
        result <- dbService.createCase(create)
      } yield result

      assertZIO(effect.either) {
        Assertion.isLeft
      }
    }

  override def spec =
    suite("CaseSpec")(

      suite("DatabaseService ZLayer")(
        createTableTest,
        suite("")(
          caseLifecycleTest,
          clearTableTest
        ) @@ nonFlaky(3),
        pbtCreateCase @@ nonFlaky(5),
        invalidDateFailure,
        deleteTableTest
      ).provide(
        DatabaseService.live,
        ZLayer.fromZIO(
          Hub.unbounded[CaseStatusChanged]
        ),
        PostgresConnection.live(
        "org.postgresql.Driver",
        "jdbc:postgresql://localhost:5432/casesdb",
        "postgres",
        "postgres"
        )
      ) @@ sequential,

      suite("ExternalService ZLayer")(
        flakyServiceTest
      ).provide(
        ExternalService.live(3),
        ZLayer.fromZIO(
          Hub.unbounded[CaseStatusChanged]
        )
      ) @@ diagnose(1.minute) @@ flaky(3),

      test("Property-Based Testing") {
        // 100 examples each generator by default
        check(Gen.int, Gen.int, Gen.int) { (x, y, z) =>
          // Statement must be true for all x, y, z, ...
          assertTrue(((x + y) + z) == (x + (y + z)))
        }
      }
    ) @@ timed
}
/*
  TODO: Assertion variants
    - Assertion.assertion => tests any truth value (the most general assertion)
    - Assertion.equalTo => tests for equality
    - Assertion.fails/failsCause => expects effect to fail with the exact (typed) failure/cause specified
    - Assertion.dies => expects effect to fail with a Throwable that was not part of the type signature (defects)
    - Assertion.isInterrupted => validates an interruption on the effect
    - Specialized:
      - isLeft/isRight for Either
      - isSome/isNone for Option
      - isSuccess/isFailure for Try
      - isEmpty/isNonEmpty/contains/hasSize/has* for Iterable
      - isEmptyString/nonEmptyString/startsWithString/containsString/matchesRegex for String
      - isLessThan/isGreaterThan
 */
/*
  TODO: Aspects
    - timeout(duration)
    - eventually - retries a test until success
    - flaky(retries) - flaky tests with a limit
    - nonFlaky(n) - repeats n times, stops at first failure
    - repeats(n) - same
    - retries(n) - retries n times, stops at first success
    - debug - prints everything it can to the console
    - silent - prints nothing
    - diagnose(duration) - timeout with fiber dump explaining what happened
    - parallel/sequential (aspects of a suite, not a single test)
    - ignore - skips test(s)
    - success - fail all ignored tests
    - timed - measure execution time
    - before/beforeAll + after/afterAll
 */
/*
  TODO: [Gen]erators
    - int
    - char, alphaChar, alphaNumericChar, asciiChar, hexChar, printableChar
    - string, stringN
    - const
    - elements
    - fromIterable(n to m)
    - uniform - select doubles between 0 and 1
    - fromRandom(...)
    - fromZIO(...)
    - unfoldGen
    - Specialized:
      - listOf
      - setOfN
      - option
      - either
    - Combinators:
      - generator.zip
      - generator.map(...)
      - generator.filter(...)
      - generator.flatMap(...)
    - for-comprehensions
 */
