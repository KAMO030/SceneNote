package dev.scenenote.core.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.scenenote.db.SceneNoteDb

class AndroidDriverFactory(private val context: Context) : DbDriverFactory {
    override fun create(): SqlDriver = AndroidSqliteDriver(SceneNoteDb.Schema, context, "scenenote.db")
}
