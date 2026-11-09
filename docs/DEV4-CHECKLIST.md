# Checklist DEV4

- [ ] Ruta `/notificaciones/**` correcta
- [ ] Endpoint `/empleados/{id}/completo` con fallback
- [ ] `setup-secrets.sh` ejecutable
- [ ] `.env.example` sin secretos reales
- [ ] Jenkins + SonarQube en Compose
- [ ] 7 Jenkinsfiles con Quality Gate del 70%
- [ ] 7 `sonar-project.properties`
- [ ] 3 redes segmentadas con `internal: true` en `data-broker-net`
- [ ] `verify-networks.sh` pasa
- [ ] `verify-failfast.sh` pasa
