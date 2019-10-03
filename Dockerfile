FROM java:alpine

MAINTAINER @agafox <agafox@sip3.io>
MAINTAINER @windsent <windsent@sip3.io>

RUN apk update && \
    apk add openssl

ENV SERVICE_NAME sip3-salto
ENV HOME /opt/$SERVICE_NAME

ENV EXECUTABLE_FILE $HOME/$SERVICE_NAME.jar
ADD target/$SERVICE_NAME*.jar $EXECUTABLE_FILE

ENV CONFIG_FILE $HOME/application.yml
ADD src/main/resources/application.yml $CONFIG_FILE

ENV CODECS_FILE $HOME/codecs.yml
ADD src/main/resources/codecs.yml $CODECS_FILE

ENV LOGBACK_FILE $HOME/logback.xml
ADD src/main/resources/logback.xml $LOGBACK_FILE

ENV JAVA_OPTS "-Xms256m -Xmx512m"
ENTRYPOINT java $JAVA_OPTS -jar $EXECUTABLE_FILE -Dconfig.location=$CONFIG_FILE -Dlogback.configurationFile=$LOGBACK_FILE -Dcodecs.location=$CODECS_FILE