# Imagen del contenedor de PayAF. El jar se construye antes con Maven y se copia aquí
# (lo hace el script deploy.ps1), así la imagen no necesita Maven ni el código fuente.
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 payaf
WORKDIR /app
COPY app.jar /app/app.jar
USER payaf

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
