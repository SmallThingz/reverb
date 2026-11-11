cmake_minimum_required(VERSION 3.22.1)
project(reverb_file_move LANGUAGES C)
add_library(reverb_file_move SHARED no_replace_file_move.c)
set_target_properties(reverb_file_move PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
target_compile_options(reverb_file_move PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(reverb_file_move PRIVATE
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--no-undefined")
