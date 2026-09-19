-- One database per service, on the shared Postgres instance.
--
-- The postgres image runs this only when its data volume is empty, i.e. on the
-- very first start. An existing postgres_data volume is left untouched, so this
-- never recreates or drops anything that is already there.
--
-- Each service creates its own tables on startup (ddl-auto: update). This file
-- only has to make the databases exist. AuthUser has no database.

CREATE DATABASE accountsdb;
CREATE DATABASE customerdb;
CREATE DATABASE billerdb;
CREATE DATABASE paymentdb;
CREATE DATABASE billpayworkerdb;
CREATE DATABASE settlementdb;
CREATE DATABASE aicommercedb;
