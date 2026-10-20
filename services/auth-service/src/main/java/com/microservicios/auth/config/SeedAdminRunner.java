package com.microservicios.auth.config;

import com.microservicios.auth.model.UserAccount;
import com.microservicios.auth.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Crea el administrador semilla al arrancar.
 *
 * <p>Sin él no habría forma de empezar: los endpoints de escritura exigen rol
 * ADMIN y las cuentas de empleado nacen con rol USER desde {@code empleado.creado}.
 * El enunciado contempla exactamente este mecanismo ("un usuario Administrador
 * semilla creado por base de datos o variable de entorno al levantar el servicio").
 *
 * <p>Es idempotente: si la cuenta ya existe no la toca, de modo que un reinicio no
 * sobrescribe una contraseña que el administrador haya cambiado.
 */
@Configuration
public class SeedAdminRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedAdminRunner.class);

    @Bean
    public ApplicationRunner sembrarAdministrador(
            UserAccountRepository repository,
            PasswordEncoder passwordEncoder,
            @Value("${auth.seed-admin.empleado-id}") String empleadoId,
            @Value("${auth.seed-admin.email}") String email,
            @Value("${auth.seed-admin.password}") String password) {

        return args -> {
            if (repository.existsById(empleadoId)) {
                log.info("[AUTH] Administrador semilla '{}' ya existe; no se modifica", empleadoId);
                return;
            }
            if (repository.existsByEmailIgnoreCase(email)) {
                log.warn("[AUTH] Ya existe una cuenta con el email '{}'; "
                        + "no se crea el administrador semilla", email);
                return;
            }

            repository.save(UserAccount.administradorSemilla(
                    empleadoId, email, passwordEncoder.encode(password)));

            log.info("[AUTH] Administrador semilla creado | empleadoId='{}' | email='{}' | rol=ADMIN",
                    empleadoId, email);
        };
    }
}
