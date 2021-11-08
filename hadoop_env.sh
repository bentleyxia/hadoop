proto=/opt/deps/protobuf/3.7.1
export PATH=${proto}/bin:$PATH
export C_INCLUDE_PATH=${proto}/include:$C_INCLUDE_PATH
export LD_LIBRARY_PATH=${proto}/lib:$LD_LIBRARY_PATH
export LIBRARY_PATH=${proto}/lib:$LIBRARY_PATH
export CMAKE_PREFIX_PATH=${proto}/inlcude:${proto}/lib
