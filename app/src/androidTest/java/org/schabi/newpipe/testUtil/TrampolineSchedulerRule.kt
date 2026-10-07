package org.schabi.newpipe.testUtil

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Redirects RxJava's computation, IO, new-thread and single schedulers to
 * [Schedulers.trampoline] for each test. The RxAndroid hook redirects only
 * main-thread scheduler initialization; it cannot replace an already initialized scheduler.
 * Other threading is unaffected.
 */
class TrampolineSchedulerRule : TestRule {

    private val scheduler = Schedulers.trampoline()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                RxJavaPlugins.setComputationSchedulerHandler { scheduler }
                RxJavaPlugins.setIoSchedulerHandler { scheduler }
                RxJavaPlugins.setNewThreadSchedulerHandler { scheduler }
                RxJavaPlugins.setSingleSchedulerHandler { scheduler }
                RxAndroidPlugins.setInitMainThreadSchedulerHandler { scheduler }

                base.evaluate()
            } finally {
                RxJavaPlugins.reset()
                RxAndroidPlugins.reset()
            }
        }
    }
}
