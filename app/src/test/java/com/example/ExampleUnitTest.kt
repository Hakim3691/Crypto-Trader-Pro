package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Feature: host-side unit-test harness sanity.
 *
 * Kept so a broken test runtime fails loudly before the domain suites run.
 */
class ExampleUnitTest {

    @Test
    fun `should add two and two on the host test runtime`() {
        // given
        val two = 2

        // when
        val sum = two + two

        // then
        assertEquals(4, sum)
    }
}
