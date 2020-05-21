FROM gradle:6.4-jdk8

WORKDIR /code

COPY . /code

EXPOSE 8078

CMD ["gradle", "run"]
