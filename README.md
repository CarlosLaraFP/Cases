# Case Management Service

Create a Scala service for managing "cases". It should be possible to create, list, update, and delete cases. The precise API is up to you.

A case has a unique id (your choice on ID generation and representation), name, date of birth, optional date of death, status, created timestamp, and status change timestamp.

A case status can be: 'pending', 'under_review', 'deficient', 'submitted'.

When a case status changes, an appropriate message should be published by the API to some "external" service. (This service can be mocked). Assume that this publish service is flaky, and that publishing may need to be retried.

The application should be wired up using ZIO layers.

The API should use Caliban.

The backing server should be zio-based (zio-http is fine but its very unstable, so the zio-wrapped Netty backend may be a better choice).

The cases should be stored in Postgresql.

Some amount of unit or integration testing (whatever is your preferred style) should be implemented, but can be barebones; it's more about the testing setup.
