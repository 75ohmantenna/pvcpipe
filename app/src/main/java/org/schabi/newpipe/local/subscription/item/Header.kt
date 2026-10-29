package org.schabi.newpipe.local.subscription.item

import android.view.View
import androidx.core.view.ViewCompat
import com.xwray.groupie.viewbinding.BindableItem
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.SubscriptionHeaderBinding
import org.schabi.newpipe.util.ServiceHelper

class Header private constructor(private val title: String?, private val serviceId: Int?) : BindableItem<SubscriptionHeaderBinding>() {

    constructor(title: String) : this(title, null)

    constructor(serviceId: Int) : this(null, serviceId)

    override fun getLayout(): Int = R.layout.subscription_header

    override fun bind(viewBinding: SubscriptionHeaderBinding, position: Int) {
        val view = viewBinding.root
        view.text = when {
            serviceId == null -> title
            ServiceHelper.hasService(serviceId) -> ServiceHelper.getNameOfServiceById(serviceId)
            else -> view.resources.getString(R.string.subscription_unavailable_service, serviceId)
        }
        ViewCompat.setAccessibilityHeading(view, true)
    }

    override fun initializeViewBinding(view: View) = SubscriptionHeaderBinding.bind(view)
}
