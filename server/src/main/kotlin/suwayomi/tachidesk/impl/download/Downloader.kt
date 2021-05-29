package suwayomi.tachidesk.impl.download

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.impl.Chapter.getChapter
import suwayomi.tachidesk.impl.Page.getPageImage
import suwayomi.tachidesk.impl.download.model.DownloadChapter
import suwayomi.tachidesk.impl.download.model.DownloadState.Downloading
import suwayomi.tachidesk.impl.download.model.DownloadState.Error
import suwayomi.tachidesk.impl.download.model.DownloadState.Finished
import suwayomi.tachidesk.impl.download.model.DownloadState.Queued
import suwayomi.tachidesk.model.table.ChapterTable
import java.util.concurrent.CopyOnWriteArrayList

class Downloader(private val downloadQueue: CopyOnWriteArrayList<DownloadChapter>, val notifier: () -> Unit) : Thread() {
    var shouldStop: Boolean = false

    class DownloadShouldStopException : Exception()

    fun step() {
        notifier()
        synchronized(shouldStop) {
            if (shouldStop) throw DownloadShouldStopException()
        }
    }

    override fun run() {
        do {
            val download = downloadQueue.firstOrNull { it.state == Queued } ?: break

            try {
                download.state = Downloading
                step()

                download.chapter = runBlocking { getChapter(download.chapterIndex, download.mangaId) }
                step()

                val pageCount = download.chapter!!.pageCount!!
                for (pageNum in 0 until pageCount) {
                    runBlocking { getPageImage(download.mangaId, download.chapterIndex, pageNum) }
                    // TODO: retry on error with 2,4,8 seconds of wait
                    // TODO: download multiple pages at once, possible solution: rx observer's strategy is used in Tachiyomi
                    // TODO: fine grained download percentage
                    download.progress = (pageNum + 1).toFloat() / pageCount
                    step()
                }
                download.state = Finished
                transaction {
                    ChapterTable.update({ (ChapterTable.manga eq download.mangaId) and (ChapterTable.chapterIndex eq download.chapterIndex) }) {
                        it[isDownloaded] = true
                    }
                }
                step()
            } catch (e: DownloadShouldStopException) {
                println("Downloader was stopped")
                downloadQueue.filter { it.state == Downloading }.forEach { it.state = Queued }
            } catch (e: Exception) {
                println("Downloader faced an exception")
                downloadQueue.filter { it.state == Downloading }.forEach { it.state = Error }
                e.printStackTrace()
            } finally {
                notifier()
            }
        } while (!shouldStop)
    }
}