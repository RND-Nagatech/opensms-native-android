package org.daslearning.opensms.next

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.daslearning.opensms.next.data.AppDatabase
import org.daslearning.opensms.next.data.ClientRepository
import org.daslearning.opensms.next.domain.TokenVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiClientLifecycleTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ClientRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ClientRepository(database, TestTokenVerifier)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun createRotateAndRevokeChangesSecretButKeepsClientIdentity() = runBlocking {
        val created = repository.createClient("sender", setOf("messages:write"), 1L)
        assertEquals(created.clientId, repository.authenticate(created.token)?.id)
        val rotated = repository.rotateClientKey(created.clientId, 2L)!!
        assertEquals(created.clientId, rotated.clientId)
        assertNotEquals(created.token, rotated.token)
        assertNull(repository.authenticate(created.token))
        assertEquals(created.clientId, repository.authenticate(rotated.token)?.id)
        assertEquals(true, repository.revokeClient(created.clientId, 3L))
        assertNull(repository.authenticate(rotated.token))
    }

    private object TestTokenVerifier : TokenVerifier {
        private var next = 0
        override fun createToken() = "test-key-${++next}-with-sufficient-length"
        override fun verifier(token: String) = "verifier:$token"
        override fun fingerprintPayload(payload: String) = "fingerprint:$payload"
    }
}
