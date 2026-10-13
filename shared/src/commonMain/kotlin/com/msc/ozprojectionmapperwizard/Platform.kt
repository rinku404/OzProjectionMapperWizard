package com.msc.ozprojectionmapperwizard

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform