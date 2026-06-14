package com.junai.app

import java.io.Serializable

data class SongItem(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val duration: Long
) : Serializable
