package io.github.mercurievv.home_automation.state

import io.github.mercurievv.cats.Id2Resource

import cats.Id
import cats.data.Kleisli
import cats.syntax.all.*

import cats.effect.kernel.{Async, Resource}

import io.circe.{Json, parser}

import doobie.*
import doobie.free.connection
import doobie.implicits.*

case class DbTable[F[_]](
  transactor: Transactor[F],
  tableName: String):
  val tableNameFragment: Fragment = Fragment.const(tableName)

case class KVStore[F[_], K, V](
  get: Kleisli[F, K, Option[V]],
  put: Kleisli[F, (K, V), Unit],
  delete: Kleisli[F, K, Unit],
  all: Kleisli[F, Unit, Map[K, V]])

object KVStore:

  /** Meta instance for circe Json — serialised as a TEXT column. */
  given jsonMeta: Meta[Json] =
    Meta[String].imap(s => parser.parse(s).getOrElse(Json.Null))(_.noSpaces)

  def create[F[_]: Async, K: Meta, V: Meta]
    : Kleisli[Resource[F, *], String, (KVStore[F, K, V], AtomicUpdate[F, K, V])] = KVStore.file[F] >>>
    KVStore.fromTransactor("kv_store").mapK(Id2Resource[F]) >>>
    (KVStore.fromDbTable[F, K, V] &&& Kleisli(v => Id2Resource[F](AtomicUpdate.sqlite(v))))

  /** Step 1a: file path → Transactor */
  def file[F[_]: Async]: Kleisli[Resource[F, *], String, Transactor[F]] =
    Kleisli { path =>
      Resource.pure(
        Transactor.fromDriverManager[F](
          driver     = "org.sqlite.JDBC",
          url        = s"jdbc:sqlite:$path",
          logHandler = None,
        ),
      )
    }

  /** Step 1b: in-memory connection → Transactor */
  def inMemory[F[_]: Async]: Kleisli[Resource[F, *], Unit, Transactor[F]] =
    Kleisli.liftF(
      Resource
        .make(
          Async[F].delay {
            Class.forName("org.sqlite.JDBC")
            java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
          },
        )(conn => Async[F].delay(conn.close()))
        .map(conn => Transactor.fromConnection[F](conn, logHandler = None)),
    )

  /** Step 2: Transactor → DbTable (pure, Id) */
  def fromTransactor[F[_]](tableName: String = "kv_store"): Kleisli[Id, Transactor[F], DbTable[F]] =
    Kleisli(DbTable(_, tableName))

  /** Step 3a: DbTable → KVStore */
  def fromDbTable[F[_]: Async, K: Meta, V: Meta]: Kleisli[Resource[F, *], DbTable[F], KVStore[F, K, V]] =
    Kleisli { dbt =>
      Resource.eval(initSchema(dbt.tableNameFragment, dbt.transactor)).map { _ =>
        KVStore(
          get = Kleisli(k =>
            (sql"SELECT value FROM " ++ dbt.tableNameFragment ++ sql" WHERE key = $k")
              .query[V]
              .option
              .transact(dbt.transactor),
          ),
          put = Kleisli { case (k, v) =>
            (sql"INSERT INTO " ++ dbt.tableNameFragment ++ sql" (key, value) VALUES ($k, $v)" ++
              sql" ON CONFLICT(key) DO UPDATE SET value = excluded.value").update.run
              .transact(dbt.transactor)
              .void
          },
          delete = Kleisli(k =>
            (sql"DELETE FROM " ++ dbt.tableNameFragment ++ sql" WHERE key = $k").update.run
              .transact(dbt.transactor)
              .void,
          ),
          all = Kleisli(_ =>
            (sql"SELECT key, value FROM " ++ dbt.tableNameFragment)
              .query[(K, V)]
              .to[List]
              .transact(dbt.transactor)
              .map(_.toMap),
          ),
        )
      }
    }

  /** Step 3b: DbTable → AtomicUpdate (pure, Id) */
  def fromDbTableUpdate[F[_]: Async, K: Meta, V: Meta]: Kleisli[Id, DbTable[F], AtomicUpdate[F, K, V]] =
    Kleisli(AtomicUpdate.sqlite)

  private def initSchema[F[_]: Async](
    table: Fragment,
    xa: Transactor[F],
  ): F[Unit] =
    (sql"CREATE TABLE IF NOT EXISTS " ++ table ++
      fr"(key TEXT PRIMARY KEY, value TEXT NOT NULL)").update.run.transact(xa).void

case class AtomicUpdate[F[_], K, V](
  /** return Prev, Next
    */
  update: Kleisli[F, (K, Option[V] => Option[V]), (Option[V], Option[V])])

object AtomicUpdate:

  def sqlite[F[_]: Async, K: Meta, V: Meta](dbTable: DbTable[F]): AtomicUpdate[F, K, V] =
    AtomicUpdate(
      update = Kleisli { case (key, f) =>
        val txn =
          for
            prev <- (sql"SELECT value FROM " ++ dbTable.tableNameFragment ++ sql" WHERE key = $key").query[V].option
            next = f(prev)
            _ <- next match
              case `prev`  => connection.pure(0)
              case Some(v) =>
                (sql"INSERT INTO " ++ dbTable.tableNameFragment ++ sql" (key, value) VALUES ($key, $v)" ++
                  sql" ON CONFLICT(key) DO UPDATE SET value = excluded.value").update.run
              case None =>
                (sql"DELETE FROM " ++ dbTable.tableNameFragment ++ sql" WHERE key = $key").update.run
          yield (prev, next)
        txn.transact(dbTable.transactor)
      },
    )
