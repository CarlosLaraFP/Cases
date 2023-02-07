# Case Management Service

Create a Scala service for managing "cases". It should be possible to create, list, update, and delete cases. The precise API is up to you.

A case has a unique id (your choice on ID generation and representation), name, date of birth, optional date of death, status, created timestamp, and status change timestamp.

A case status can be: 'pending', 'under_review', 'deficient', 'submitted'.

When a case status changes, an appropriate message should be published by the API to some "external" service. (This service can be mocked). Assume that this publish service is flaky, and that publishing may need to be retried.

Additional requirements:

* The application should be wired up using ZIO layers.
* The API should use Caliban.
* The backing server should be zio-based (zio-http is fine but its very unstable, so the zio-wrapped Netty backend may be a better choice).
* The cases should be stored in Postgresql.
* Some amount of unit or integration testing (whatever is your preferred style) should be implemented, but can be barebones; it's more about the testing setup.

# PostgreSQL Setup

Before running CaseApp or CaseSpec, run these commands to set up PostgreSQL locally (Mac terminal):

* /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
* brew install postgresql
* initdb /usr/local/var/postgres
* pg_ctl -D /usr/local/var/postgres start
* createdb casesdb
* psql casesdb
* CREATE USER postgres WITH LOGIN PASSWORD 'postgres';
* GRANT ALL PRIVILEGES ON DATABASE casesdb TO postgres;

# Next Steps

* Add cats.data (with ZIO interop) to validate API inputs
* Add property-based testing
* Increase unit and integration test coverage, including failure test cases
* Use zio-config to extract configuration values from environment variables
* Improve 'updateCase' API to allow Case changes beyond CaseStatus
* Use functional streaming library fs2 for the 'listCases' API
* Add domain error model
* Decouple DatabaseService from ExternalService by adding GraphQL Subscriptions (Hub, ZStream, ZPipeline, and ZSink)
* Deploy GraphQL API as a serverless microservice (CodePipeline, CodeBuild, Docker, ECR, ALB -> ECS/EKS Fargate with horizontal auto-scaling, SQS)
* GraphQL API load testing to determine service stability as concurrency and RPS increase