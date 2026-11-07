package com.vbkforge.bridge

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Decides whether the current screen is sensitive (password, OTP, payment, banking).
 * If it is, Bridge pauses and hands control back to you. Errs on the side of pausing.
 */
class SafetyGuard(private val svc: BridgeService) {

    /** Apps never checked: Bridge itself and the Claude app (its chat text would cause false alarms). */
    private val ignoredPackages = setOf(svc.packageName, "com.anthropic.claude")

    // Package names (or parts of them) of banking, payment and money apps.
    private val financialPackageParts = listOf(
        "bank", "paypal", "paytm", "phonepe", "npci", "wallet", "venmo", "squareup.cash",
        "revolut", "transferwise", "coinbase", "binance", "klarna", "zellepay", "paisa",
        "com.chase.", "com.infonow.bofa", "com.wf.wellsfargo", "com.citi.", "capitalone",
        "americanexpress", "discoverfinancial", "com.pnc.", "com.usaa.", "barclays", "hsbc",
        "natwest", "lloyds", "santander", "monzo", "getmondo", "starling", "com.sbi.",
        "hdfc", "icici", "com.axis.", "kotak", "emiratesnbd", "adcb", "mashreq", "alrajhi",
        "com.samsung.android.spay", "stcpay", "payoneer", "skrill", "remitly", "westernunion"
    )

    private val financialLabel = Regex("""\b(bank|banking|wallet|pay)\b""", RegexOption.IGNORE_CASE)

    // Text in or about an input field that means "one-time code".
    private val otpField = Regex(
        """\b(otp|one[- ]?time|verification code|security code|auth(entication)? code|2fa|two[- ]factor|passcode|sms code|confirmation code|pin)\b""",
        RegexOption.IGNORE_CASE
    )

    // Text in or about an input field that means "payment details".
    private val paymentField = Regex(
        """\b(card ?number|credit card|debit card|name on card|cvv2?|cvc|csc|expiry|expiration|mm ?/ ?yy|upi|iban|routing number|account number|sort code|swift)\b""",
        RegexOption.IGNORE_CASE
    )

    // Visible text that means the screen is asking for a one-time code.
    private val otpScreen = Regex(
        """\b(otp|one[- ]time (password|passcode|code)|verification code|enter (the )?(\d[- ]digit )?code|code (we )?(sent|texted))\b""",
        RegexOption.IGNORE_CASE
    )

    // Visible text that means the screen is a payment step.
    private val paymentScreen = Regex(
        """\b(pay now|confirm (and )?pay(ment)?|complete (your )?purchase|place (your )?order|proceed to pay(ment)?|payment method|payment details|enter (your )?upi pin|add (a )?(credit |debit )?card|billing address|one-tap buy|subscribe now)\b""",
        RegexOption.IGNORE_CASE
    )

    private val labelCache = HashMap<String, String>()

    fun appLabel(pkg: String): String = synchronized(labelCache) {
        labelCache.getOrPut(pkg) {
            try {
                val pm = svc.packageManager
                @Suppress("DEPRECATION")
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                pkg
            }
        }
    }

    fun isFinancialApp(pkg: String): Boolean {
        if (pkg in ignoredPackages) return false
        val p = pkg.lowercase()
        if (financialPackageParts.any { p.contains(it) }) return true
        val label = appLabel(pkg)
        return label != pkg && financialLabel.containsMatchIn(label)
    }

    /** Returns a plain-words reason if the screen is sensitive, or null if it is safe to act. */
    fun check(): String? {
        for (root in svc.screenRoots(includeKeyboard = false)) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg in ignoredPackages) continue
            val app = appLabel(pkg)
            if (isFinancialApp(pkg)) return "a banking or payment app ($app) is open"
            val reason = scanTree(root)
            if (reason != null) return "$reason in $app"
        }
        return null
    }

    private fun scanTree(root: AccessibilityNodeInfo): String? {
        val visibleText = StringBuilder()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < 4000) {
            val (node, depth) = stack.removeLast()
            visited++
            if (node.isPassword) return "a password field"
            if (node.isEditable) {
                val about = listOfNotNull(
                    node.hintText, node.text, node.contentDescription, node.viewIdResourceName,
                    node.paneTitle, node.tooltipText
                ).joinToString(" ")
                    .replace('_', ' ')
                if (otpField.containsMatchIn(about)) return "a one-time code (OTP) field"
                if (paymentField.containsMatchIn(about)) return "a payment field"
            }
            if (node.isVisibleToUser) {
                node.text?.let { if (it.length < 300) visibleText.append(it).append(" | ") }
                node.contentDescription?.let { if (it.length < 300) visibleText.append(it).append(" | ") }
            }
            if (depth < 80) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                }
            }
        }
        val text = visibleText.toString()
        if (otpScreen.containsMatchIn(text)) return "a one-time code (OTP) screen"
        if (paymentScreen.containsMatchIn(text)) return "a payment screen"
        return null
    }
}
