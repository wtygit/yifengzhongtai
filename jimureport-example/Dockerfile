FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/java:17-anolis

MAINTAINER jeecgos@163.com

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

RUN mkdir -p /jimureport

WORKDIR /jimureport

EXPOSE 8085

ADD ./target/jimureport-example-2.2.jar ./

CMD java -DMYSQL-HOST=jimureport-mysql -Dfile.encoding=utf-8 -Djava.security.egd=file:/dev/./urandom -jar jimureport-example-2.2.jar