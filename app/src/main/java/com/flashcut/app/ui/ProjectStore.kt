package com.flashcut.app.ui

import android.content.Context
import com.flashcut.app.data.Project
import com.flashcut.app.data.SourceClip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 轻量工程持久化：把工程 JSON 写到 filesDir/projects/<id>.json。 */
object ProjectStore {

    private fun dir(context: Context) = File(context.filesDir, "projects").apply { mkdirs() }

    fun save(context: Context, project: Project) {
        val json = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("sources", JSONArray().apply {
                project.sources.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("uri", s.uri.toString())
                        put("name", s.displayName)
                        put("duration", s.durationMs)
                        put("w", s.width)
                        put("h", s.height)
                    })
                }
            })
        }
        File(dir(context), project.id + ".json").writeText(json.toString())
    }

    /** 列出全部工程，按修改时间倒序。 */
    fun list(context: Context): List<Project> =
        dir(context).listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f -> runCatching { load(context, f.nameWithoutExtension) }.getOrNull() }
            ?.filter { it.id.isNotBlank() }
            ?: emptyList()

    fun delete(context: Context, id: String) {
        File(dir(context), id + ".json").delete()
    }

    fun load(context: Context, id: String): Project {
        if (id.isBlank()) return Project("", "", emptyList(), emptyList())
        return runCatching {
            val json = JSONObject(File(dir(context), id + ".json").readText())
            val arr = json.getJSONArray("sources")
            val sources = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SourceClip(
                    id = o.getString("id"),
                    uri = android.net.Uri.parse(o.getString("uri")),
                    displayName = o.getString("name"),
                    durationMs = o.getLong("duration"),
                    width = o.optInt("w"),
                    height = o.optInt("h"),
                )
            }
            Project.fromSources(json.optString("id"), json.optString("name", "我的成片"), sources)
        }.getOrDefault(Project("", "", emptyList(), emptyList()))
    }
}
