cmake_minimum_required(VERSION 3.22.1)
project(kormium_sqlite_ext C)

# The shim only needs libdl; it deliberately does not link androidx's libsqliteJni.so, resolving
# sqlite3_auto_extension at runtime instead so that this builds without the AAR's native artifacts.
add_library(kormium_sqlite_ext SHARED kormium_sqlite_ext.c)
target_link_libraries(kormium_sqlite_ext dl)
