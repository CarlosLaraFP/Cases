package com.company.cases
/*
 Caliban requires implicit Schema(s) in scope at compile time to handle generic IO effects
 (otherwise, apiInterpreter.mapError(_.getCause) to surface exceptions to users)
 */
import caliban.GraphQL.graphQL
import caliban.ZHttpAdapter
import zhttp.http._
import zhttp.service.Server
import zio.{Hub, ZIO, ZIOAppDefault, ZLayer}

import scala.language.postfixOps


object CaseApp extends ZIOAppDefault {
  /*
    ZIO HTTP handles each incoming request in its own Fiber out-of-the-box.

    Builds a GraphQL API for the given resolver. It requires an instance of Schema for each operation type.
    A root resolver contains resolvers for the 3 types of operations allowed in GraphQL: queries, mutations, and subscriptions.
    A resolver is a simple value of the case class describing the API.
  */
  private val app: ZIO[CaseService, Throwable, Nothing] =
    for {
      caseService <- ZIO.service[CaseService]
      rootResolver <- caseService.rootResolver
      api = graphQL(rootResolver)
      apiInterpreter <- api.interpreter
      interpreter = apiInterpreter
      server <- Server
        .start(
          port = 8088,
          http = Http.collectHttp {
            case _ -> !! / "api" / "graphql" =>
              ZHttpAdapter.makeHttpService(interpreter)
            case _ -> !! / "ws" / "graphql" =>
              ZHttpAdapter.makeWebSocketService(interpreter)
          }
        )//.forever
    } yield server

  override def run: ZIO[Any, Throwable, Nothing] =
    // Dependency injection with ZLayers / ZIO magic
    app.provide(
      CaseService.live,
      DatabaseService.live,
      ExternalService.live(10),
      ZLayer.fromZIO(
        Hub.unbounded[CaseStatusChanged]
      ),
      PostgresConnection.live(
        "org.postgresql.Driver",
        "jdbc:postgresql://localhost:5432/casesdb",
        "postgres",
        "postgres"
      ),
      ZLayer.Debug.mermaid
    )
    // TODO: the hard-coded configuration values above can be extracted from the host Docker container using ZConfig.fromSystemEnv
}
/*
    createTable:
      curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"mutation{\n modifyTable(action: Create){\n result\n}\n}"}'

    deleteTable:
      curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"mutation{\n modifyTable(action: Delete){\n result\n}\n}"}'

    clearTable:
      curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"mutation{\n modifyTable(action: Clear){\n result\n}\n}"}'

    listCases:
      curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"query{\n listCases(status: Pending){\n name\n status\n}\n}"}'

    createCase:
      curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"mutation{\n createCase(name: \"Litigatable\", dateOfBirth: \"1990-11-12\"){\n result\n}\n}"}'

    updateCase:
      curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"mutation{\n updateCase(id: \"ea1012f8-4886-44c1-8295-bc53ce0f9c5e\", status: UnderReview){\n result\n caseId\n}\n}"}' --write-out '\n%{http_code}\n'

    deleteCase:
      curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"mutation{\n deleteCase(id: \"ea1012f8-4886-44c1-8295-bc53ce0f9c5e\"){\n result\n caseId\n}\n}"}'
   */
