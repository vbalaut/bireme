set -xeu

SOURCE_DIR=${PWD}/integration_test/${SOURCE}

cp -f ${SOURCE_DIR}/pom.xml ./pom.xml

mvn docker:start
mvn clean package -Dmaven.test.skip=true

tar -xf $(ls target/*.tar.gz) -C target
BIREME=$(ls target/*.tar.gz | awk '{print i$0}' i=$(pwd)'/' | sed -e "s/.tar.gz$//")

rm -rf ${BIREME}/etc
cp -rf ${SOURCE_DIR}/etc ${BIREME}/etc
