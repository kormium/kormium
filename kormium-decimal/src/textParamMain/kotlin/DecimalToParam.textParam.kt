package io.github.kormium.decimal

// StandardTypeMapper renders the value with toString() — decimal text every backend accepts.
internal actual fun decimalToParam(value: Decimal): Any? = value
