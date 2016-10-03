open class A(x: String = "OK") {
    val result = x
}

class B : A()

fun box() = B().result