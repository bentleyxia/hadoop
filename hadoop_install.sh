sudo echo "obtain super user"


# install proto 3.7.1
# https://github.com/apache/hadoop/blob/branch-3.3.0/BUILDING.txt
PROTO_VERSION=`protoc --version | grep -oP '[0-9](.[0-9]*){2}'`

function proto_install() {
cd /tmp
    if [ -d "protobuf" ]; then
        echo "protobuf git has exit"
    else
        git clone https://github.com/protocolbuffers/protobuf
    fi
    cd protobuf
    git checkout v3.7.1
    autoreconf -i
    ./configure --prefix=/opt/deps/protobuf/3.7.1
    make
    sudo make install
}

if [[ "$PROTO_VERSION" == "3.7.1" ]]; then
    echo "proto version ${PROTO_VERSION}"
else
    proto_install
fi

# install hadoop
cd /tmp
    if [ -d "hadoop" ]; then
        echo "hadoop git has exit"
    else
        git clone https://github.com/bentleyxia/hadoop.git
    fi
    cd hadoop
    git checkout blaze-3.3.0
    mvn clean package -Pdist,native -DskipTests -Dtar -Dmaven.javadoc.skip=true
    tar xvf hadoop-dist/target/hadoop-3.3.0.tar.gz -C /opt
