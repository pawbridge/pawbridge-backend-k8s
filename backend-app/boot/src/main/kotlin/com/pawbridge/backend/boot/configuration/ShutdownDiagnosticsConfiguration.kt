package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.shutdown.InFlightRequests
import com.pawbridge.backend.boot.shutdown.ShutdownDiagnostics
import java.time.Clock
import org.apache.catalina.core.StandardContext
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

/** Records unfinished requests on the public server and logs them when graceful shutdown has to wait. */
@Configuration(proxyBeanMethods = false)
class ShutdownDiagnosticsConfiguration {
    @Bean
    fun inFlightRequests(): InFlightRequests = InFlightRequests(Clock.systemUTC())

    @Bean
    fun inFlightRequestsFilter(requests: InFlightRequests): FilterRegistrationBean<OncePerRequestFilter> {
        val registration = FilterRegistrationBean(requests.filter())
        // First, so requests that Security or the path rewrite reject are counted too.
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }

    @Bean
    fun shutdownDiagnostics(requests: InFlightRequests, context: ApplicationContext): ShutdownDiagnostics =
        ShutdownDiagnostics(requests, { containerAsyncInProgress(context) })

    private companion object {
        /** What Spring Boot's Tomcat graceful shutdown waits on; null outside an embedded Tomcat. */
        private fun containerAsyncInProgress(context: ApplicationContext): Long? {
            val server = (context as? WebServerApplicationContext)?.webServer as? TomcatWebServer ?: return null
            return server.tomcat.host.findChildren().filterIsInstance<StandardContext>().sumOf { it.inProgressAsyncCount }
        }
    }
}
