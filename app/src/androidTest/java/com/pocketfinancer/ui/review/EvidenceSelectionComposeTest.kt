package com.pocketfinancer.ui.review

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketfinancer.data.repository.SmsReviewGrounding
import com.pocketfinancer.ui.theme.PocketFinancerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceSelectionComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourceRemainsReadOnlyAndActiveFieldIsAnnounced() {
        val source = "🔔 INR 10 debited from XX1234"
        val amount = SmsReviewGrounding.span(source, 2, 8)
        val account = SmsReviewGrounding.span(source, 22, 28)

        composeRule.setContent {
            PocketFinancerTheme {
                EvidenceSelectionText(
                    source = source,
                    selections = mapOf(
                        ReviewField.AMOUNT to amount,
                        ReviewField.ACCOUNT to account
                    ),
                    activeField = ReviewField.ACCOUNT,
                    onSelectionChanged = {}
                )
            }
        }

        composeRule.onNodeWithText(source).assertExists()
        composeRule.onNodeWithContentDescription(
            "SMS evidence. Account is active. Drag the selection handles to reselect it."
        )
            .assertExists()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
    }
}
