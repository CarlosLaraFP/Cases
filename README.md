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

Before running CaseApp or CaseSpec, please run these commands to set up PostgreSQL locally (Mac terminal):

1. /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
2. brew install postgresql
3. initdb /usr/local/var/postgres
4. pg_ctl -D /usr/local/var/postgres start
5. createdb casesdb
6. psql casesdb
7. CREATE USER postgres WITH LOGIN PASSWORD 'postgres';
8. GRANT ALL PRIVILEGES ON DATABASE casesdb TO postgres;
9. \q

When you are done:

10. pg_ctl stop -D /usr/local/var/postgres


# Next Steps

* Improve domain model through functional domain-driven design
* Increase property-based unit and integration testing, including failure test cases
* Add proactive retry to extend sequential retry (distributed systems best practices from AWS)
* Use zio-config to extract configuration values from environment variables
* Improve 'updateCase' API to allow Case changes beyond CaseStatus
* Use functional streaming library fs2 for the 'listCases' API
* Add explicit error-handling for Fiber interruptions
* Deploy GraphQL API as a serverless microservice (CodePipeline, CodeBuild, Docker, ECR, ALB -> ECS/EKS Fargate with horizontal auto-scaling, SQS, CloudWatch)
* GraphQL API load testing to determine service stability as concurrency and RPS increase
