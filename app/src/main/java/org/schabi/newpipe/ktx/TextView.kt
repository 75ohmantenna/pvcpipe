@file:JvmName("TextViewUtils")

package org.schabi.newpipe.ktx

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.animation.addListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import io.reactivex.rxjava3.android.MainThreadDisposable
import io.reactivex.rxjava3.core.Observable
import org.schabi.newpipe.MainActivity

private const val TAG = "TextViewUtils"

/**
 * Animate the text color of any view that extends [TextView] (Buttons, EditText...).
 *
 * @param duration   the duration of the animation
 * @param colorStart the text color to start with
 * @param colorEnd   the text color to end with
 */
fun TextView.animateTextColor(duration: Long, @ColorInt colorStart: Int, @ColorInt colorEnd: Int) {
    if (MainActivity.DEBUG) {
        Log.d(
            TAG,
            "animateTextColor() called with: " +
                "view = [" + this + "], duration = [" + duration + "], " +
                "colorStart = [" + colorStart + "], colorEnd = [" + colorEnd + "]"
        )
    }
    val viewPropertyAnimator = ValueAnimator.ofObject(ArgbEvaluator(), colorStart, colorEnd)
    viewPropertyAnimator.interpolator = FastOutSlowInInterpolator()
    viewPropertyAnimator.duration = duration
    viewPropertyAnimator.addUpdateListener { setTextColor(it.animatedValue as Int) }
    viewPropertyAnimator.addListener(onCancel = { setTextColor(colorEnd) }, onEnd = { setTextColor(colorEnd) })
    viewPropertyAnimator.start()
}

/**
 * Emits this view's current text on subscription, then its text after every change.
 *
 * Disposing removes the watcher on the main thread, whichever thread disposes.
 */
fun TextView.textChanges(): Observable<CharSequence> = Observable.create { emitter ->
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = emitter.onNext(s)

        override fun afterTextChanged(s: Editable) = Unit
    }
    emitter.setDisposable(object : MainThreadDisposable() {
        override fun onDispose() = removeTextChangedListener(watcher)
    })
    addTextChangedListener(watcher)
    emitter.onNext(text)
}
