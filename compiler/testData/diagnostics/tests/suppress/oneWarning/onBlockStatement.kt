fun <T> foo(x: T) {
    var r: Any?

    @Suppress("UNCHECKED_CAST")
    r = (x as String).get("" <!CAST_NEVER_SUCCEEDS!>as<!> Int)

    <!DEBUG_INFO_SMARTCAST!>r<!>.hashCode()

    var i = 1

    if (i != 1) {
        @Suppress("UNCHECKED_CAST")
        i += (x as Int) + ("" <!CAST_NEVER_SUCCEEDS!>as<!> Int)
    }

    val l: () -> Unit = {
        @Suppress("UNCHECKED_CAST")
        i += (x as Int) + ("" <!CAST_NEVER_SUCCEEDS!>as<!> Int)
    }
    l()
}
