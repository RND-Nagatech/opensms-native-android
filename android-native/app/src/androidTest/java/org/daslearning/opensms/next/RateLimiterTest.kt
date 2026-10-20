package org.daslearning.opensms.next

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.daslearning.opensms.next.data.AppDatabase
import org.daslearning.opensms.next.data.RoomRateLimiter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RateLimiterTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun perClientWindowPersistsAcrossLimiterRecreation() = runBlocking {
        val firstLimiter = RoomRateLimiter(database)
        repeat(30) { assertTrue(firstLimiter.allow("stable-client-id")) }
        assertFalse(RoomRateLimiter(database).allow("stable-client-id"))
    }
}
