package dev.scenenote.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.scenenote.db.SceneNoteDb

class IosDriverFactory : DbDriverFactory {
    override fun create(): SqlDriver = NativeSqliteDriver(SceneNoteDb.Schema, "scenenote.db")
}
