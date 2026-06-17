package com.example.sample

import java.util.logging.Logger

/**
 * A sample Kotlin class used as a test fixture.
 */
class SampleKotlinClass(val name: String) {

    constructor(name: String, value: Int) : this(name) {
        println("Secondary constructor: $name=$value")
    }

    /**
     * Returns a greeting string.
     */
    fun greet(): String {
        return "Hello, $name!"
    }

    fun compute(x: Int, y: Int): Int {
        return x + y
    }

    class NestedHelper {
        fun help(): String = "helping"
    }
}

fun topLevelHelper(input: String): String {
    return input.uppercase()
}
