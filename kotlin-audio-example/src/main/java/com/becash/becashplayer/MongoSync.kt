package com.becash.becashplayer

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document
import org.json.JSONObject
import timber.log.Timber

object MongoSync {

    /**
     * Descarcă colecția `played` din MongoDB și returnează un map
     * _id MongoDB → tot documentul MongoDB (fără _id).
     *
     * _id din MongoDB este identic cu _id din playlist.json —
     * comparăm direct, fără nicio transformare.
     *
     * [localFiles] = set de _id din playlist.json
     */
    suspend fun sync(mongoUrl: String, localFiles: Set<String>): Map<String, JSONObject> =
        withContext(Dispatchers.IO) {
            val client = MongoClient.create(mongoUrl)
            try {
                val collection = client
                    .getDatabase("becash_player")
                    .getCollection<Document>("played")

                val result = mutableMapOf<String, JSONObject>()

                collection.find(Filters.ne("delete", 1)).collect { doc ->
                    val mongoId = doc.getString("_id") ?: return@collect
                    if (mongoId in localFiles) {
                        result[mongoId] = docToJson(doc)
                    }
                }

                Timber.i("MongoSync: ${result.size}/${localFiles.size} cântece sincronizate")
                result
            } finally {
                client.close()
            }
        }

    /** Convertește un Document BSON în JSONObject, fără câmpul _id */
    private fun docToJson(doc: Document): JSONObject {
        val json = JSONObject()
        doc.forEach { (key, value) ->
            if (key == "_id") return@forEach
            when (value) {
                null        -> json.put(key, JSONObject.NULL)
                is Boolean  -> json.put(key, value)
                is Int      -> json.put(key, value)
                is Long     -> json.put(key, value)
                is Double   -> json.put(key, value)
                is String   -> json.put(key, value)
                else        -> json.put(key, value.toString())
            }
        }
        return json
    }
}