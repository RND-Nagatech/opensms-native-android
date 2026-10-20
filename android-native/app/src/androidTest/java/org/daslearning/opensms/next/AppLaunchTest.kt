package org.daslearning.opensms.next

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AppLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesWithGatewayOff() {
        composeRule.onNodeWithText("OpenSMS Next").assertIsDisplayed()
        composeRule.onNodeWithText("Hentikan gateway", substring = false).assertDoesNotExist()
    }
}
