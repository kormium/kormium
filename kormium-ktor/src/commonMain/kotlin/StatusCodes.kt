package io.github.kormium.ktor

import io.github.kormium.CheckViolationException
import io.github.kormium.ForeignKeyViolationException
import io.github.kormium.KormiumException
import io.github.kormium.NotNullViolationException
import io.github.kormium.QueryException
import io.github.kormium.UniqueViolationException
import io.ktor.http.HttpStatusCode

/**
 * Maps a [KormiumException] to a sensible HTTP status code:
 * - [UniqueViolationException] -> 409 Conflict
 * - [ForeignKeyViolationException] / [NotNullViolationException] / [CheckViolationException] -> 400 Bad Request
 * - any other [QueryException] / [KormiumException] -> 500 Internal Server Error
 *
 * This is a pure function on purpose — kormium-ktor does not install a `StatusPages` handler or
 * dictate a response body. Plug it into your own handler so you control the body format:
 * ```
 * install(StatusPages) {
 *     exception<KormiumException> { call, e -> call.respond(e.httpStatusCode(), ErrorDto(e.message)) }
 * }
 * ```
 */
public fun KormiumException.httpStatusCode(): HttpStatusCode = when (this) {
    is UniqueViolationException -> HttpStatusCode.Conflict
    is ForeignKeyViolationException -> HttpStatusCode.BadRequest
    is NotNullViolationException -> HttpStatusCode.BadRequest
    is CheckViolationException -> HttpStatusCode.BadRequest
    is QueryException -> HttpStatusCode.InternalServerError
    else -> HttpStatusCode.InternalServerError
}
