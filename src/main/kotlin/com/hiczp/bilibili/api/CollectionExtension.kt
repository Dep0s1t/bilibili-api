package com.hiczp.bilibili.api

import kotlin.experimental.ExperimentalTypeInference

@UseExperimental(ExperimentalTypeInference::class)
internal inline fun <T> list(@BuilderInference block: MutableList<T>.() -> Unit): List<T> {
    val list = ArrayList<T>()
    block(list)
    return list
}
