package com.wyspr.core.transport

interface PaymentNotifier {

    fun notifyInboundPayment(
        peerPub: ByteArray?,
        peerName: String?,
        amountAtomicUnits: Long,
        txHash: String,
    )

    fun setActiveWalletScreen(active: Boolean)

    object NoOp : PaymentNotifier {
        override fun notifyInboundPayment(
            peerPub: ByteArray?,
            peerName: String?,
            amountAtomicUnits: Long,
            txHash: String,
        ) = Unit
        override fun setActiveWalletScreen(active: Boolean) = Unit
    }
}
