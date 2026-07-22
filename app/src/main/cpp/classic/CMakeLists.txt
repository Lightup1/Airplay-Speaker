cmake_minimum_required(VERSION 3.22.1)
project(classic_raop_native C)

set(CMAKE_C_STANDARD 11)
set(SHAIRPLAY ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/shairplay)
set(SHAIRPLAY_LIB ${SHAIRPLAY}/lib)

add_library(classic_raop_native SHARED
    ${SHAIRPLAY_LIB}/base64.c
    ${SHAIRPLAY_LIB}/digest.c
    ${SHAIRPLAY_LIB}/http_parser.c
    ${SHAIRPLAY_LIB}/http_request.c
    ${SHAIRPLAY_LIB}/http_response.c
    ${SHAIRPLAY_LIB}/httpd.c
    ${SHAIRPLAY_LIB}/logger.c
    ${SHAIRPLAY_LIB}/netutils.c
    ${SHAIRPLAY_LIB}/raop.c
    ${SHAIRPLAY_LIB}/raop_buffer.c
    ${SHAIRPLAY_LIB}/raop_rtp.c
    ${SHAIRPLAY_LIB}/rsakey.c
    ${SHAIRPLAY_LIB}/rsapem.c
    ${SHAIRPLAY_LIB}/sdp.c
    ${SHAIRPLAY_LIB}/aes_ctr.c
    ${SHAIRPLAY_LIB}/pairing.c
    ${SHAIRPLAY_LIB}/utils.c
    ${SHAIRPLAY_LIB}/fairplay_dummy.c
    ${SHAIRPLAY_LIB}/plist.c
    ${SHAIRPLAY_LIB}/crypto/aes.c
    ${SHAIRPLAY_LIB}/crypto/bigint.c
    ${SHAIRPLAY_LIB}/crypto/hmac.c
    ${SHAIRPLAY_LIB}/crypto/md5.c
    ${SHAIRPLAY_LIB}/crypto/rc4.c
    ${SHAIRPLAY_LIB}/crypto/sha1.c
    ${SHAIRPLAY_LIB}/alac/alac.c
    ${SHAIRPLAY_LIB}/curve25519/curve25519-donna.c
    ${SHAIRPLAY_LIB}/ed25519/add_scalar.c
    ${SHAIRPLAY_LIB}/ed25519/fe.c
    ${SHAIRPLAY_LIB}/ed25519/ge.c
    ${SHAIRPLAY_LIB}/ed25519/keypair.c
    ${SHAIRPLAY_LIB}/ed25519/sc.c
    ${SHAIRPLAY_LIB}/ed25519/seed.c
    ${SHAIRPLAY_LIB}/ed25519/sha512.c
    ${SHAIRPLAY_LIB}/ed25519/sign.c
    ${SHAIRPLAY_LIB}/ed25519/verify.c
    classic_raop_jni.c
)

target_include_directories(classic_raop_native PRIVATE
    ${SHAIRPLAY}/include/shairplay
    ${SHAIRPLAY_LIB}
    ${SHAIRPLAY_LIB}/crypto
    ${SHAIRPLAY_LIB}/alac
    ${SHAIRPLAY_LIB}/curve25519
    ${SHAIRPLAY_LIB}/ed25519
)

# UxPlay and Shairplay use several of the same internal C symbol names. Keep
# this receiver's symbols local so both protocol engines can live in one APK.
target_compile_options(classic_raop_native PRIVATE -fvisibility=hidden)
target_link_options(classic_raop_native PRIVATE -Wl,-Bsymbolic)
target_link_libraries(classic_raop_native log m)
