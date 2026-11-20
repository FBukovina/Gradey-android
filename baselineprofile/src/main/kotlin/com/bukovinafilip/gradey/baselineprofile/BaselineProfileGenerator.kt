package com.bukovinafilip.gradey.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the app in demo mode so the code behind launch, the five tabs, a subject, the report card,
 * Gradey Recap, the school's messages and the Planner with its homework is compiled ahead of time
 * on users' devices. No account or network is needed.
 * Controls are found by their Compose test tags, so the device language does not matter.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = "com.bukovinafilip.gradey", includeInStartupProfile = true, maxIterations = 6) {
        pressHome()
        startActivityAndWait()
        enterDemo()
        for (tab in listOf("tab-subjects", "tab-absence", "tab-timetable", "tab-stravacz", "tab-today")) {
            tap(tab)
            scrollDownAndBack()
        }
        tap("tab-subjects")
        if (tap("marksReportCardRow")) { device.waitForIdle(); device.pressBack() }
        if (tap("marksRecapRow")) {
            device.wait(Until.hasObject(By.res("recapHub")), Timeout)
            scrollDownAndBack()
            // The newest recap's tile; its story plays two slides before it is closed.
            device.findObject(By.res(java.util.regex.Pattern.compile("recapRow-.*")))?.click()
            if (device.wait(Until.hasObject(By.res("recapStory")), Timeout)) {
                repeat(2) { device.click(device.displayWidth * 3 / 4, device.displayHeight / 2); device.waitForIdle() }
                tap("recapCloseButton")
            }
            device.pressBack()
        }
        tap("tab-today")
        // Messages: the list, then the first message and back out again.
        if (tap("todayKomensButton")) {
            device.wait(Until.hasObject(By.res("komensList")), Timeout)
            device.findObject(By.res(java.util.regex.Pattern.compile("komensRow-.*")))?.click()
            if (device.wait(Until.hasObject(By.res("komensMessage")), Timeout)) device.pressBack()
            device.wait(Until.hasObject(By.res("komensList")), Timeout)
            device.pressBack()
        }
        // The Planner with the school's homework, and one homework's sheet.
        if (tap("todayOpenPlannerButton")) {
            device.wait(Until.hasObject(By.res("plannerList")), Timeout)
            device.findObject(By.res(java.util.regex.Pattern.compile("homeworkItem-.*")))?.click()
            if (device.wait(Until.hasObject(By.res("homeworkDetail")), Timeout)) device.pressBack()
            device.pressBack()
        }
        tap("tab-today")
    }

    /** First run: age, consent, demo data. Later runs open straight into Today. */
    private fun MacrobenchmarkScope.enterDemo() {
        if (device.wait(Until.hasObject(By.res("tab-today")), 4_000)) return
        if (tap("welcomeAge-sixteenOrOlder", 4_000)) { tap("welcomePrivacyConsent"); tap("welcomeContinue") }
        tap("useDemoData")
        check(device.wait(Until.hasObject(By.res("tab-today")), 30_000)) { "Demo mode did not open Today." }
    }

    private fun MacrobenchmarkScope.tap(tag: String, timeout: Long = Timeout): Boolean {
        val target = device.wait(Until.findObject(By.res(tag)), timeout) ?: return false
        target.click()
        device.waitForIdle()
        return true
    }

    private fun MacrobenchmarkScope.scrollDownAndBack() {
        val x = device.displayWidth / 2
        val top = device.displayHeight / 4
        val bottom = device.displayHeight * 3 / 4
        device.swipe(x, bottom, x, top, 12)
        device.waitForIdle()
        device.swipe(x, top, x, bottom, 12)
        device.waitForIdle()
    }

    private companion object {
        const val Timeout = 8_000L
    }
}
