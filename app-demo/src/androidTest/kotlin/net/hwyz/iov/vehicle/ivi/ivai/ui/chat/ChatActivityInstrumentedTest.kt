package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation smoke tests for the chat screen (IVI-IVAI-DSN-CR-002).
 * Uses replaceText (not typeText) so the tests work on emulators whose IME
 * cannot translate strings into key events (e.g. car head-unit images).
 */
@RunWith(AndroidJUnit4::class)
class ChatActivityInstrumentedTest {

    @Test
    fun `核心元素可见且空输入不产生消息`() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            onView(withId(R.id.messageList)).check(matches(isDisplayed()))
            onView(withId(R.id.chatInput)).check(matches(isDisplayed()))
            onView(withId(R.id.sendButton)).check(matches(isDisplayed()))

            onView(withId(R.id.sendButton)).perform(click())
            onView(withId(R.id.messageList)).check { view, _ ->
                val recycler = view as RecyclerView
                assertTrue("空白输入不应产生消息", (recycler.adapter?.itemCount ?: 0) == 0)
            }
        }
    }

    @Test
    fun `输入非空点击发送后输入框清空且右侧出现用户气泡`() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            onView(withId(R.id.chatInput)).perform(replaceText("打开空调"))
            onView(withId(R.id.sendButton)).perform(click())

            // IVAI-REQ-013: 发送后输入框清空
            onView(withId(R.id.chatInput)).check(matches(withText("")))
            // 右侧用户气泡出现（乐观追加）
            onView(withText("打开空调")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun `输入框输入变化被回显`() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            onView(withId(R.id.chatInput)).perform(replaceText("我有点冷"))
            onView(withId(R.id.chatInput)).check(matches(withText("我有点冷")))
        }
    }
}
