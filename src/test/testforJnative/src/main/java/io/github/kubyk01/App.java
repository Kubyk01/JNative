/* path: src/main/java/io/github/kubyk01/App.java */

package io.github.kubyk01;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@SpringBootApplication
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    /** Create the orders table on startup. */
    @Bean
    ConnectionFactoryInitializer initializer(ConnectionFactory connectionFactory) {
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);
        initializer.setDatabasePopulator(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql")));
        return initializer;
    }

    /** Seed H2 with orders through the reactive repository (Flux). */
    @Bean
    ApplicationRunner seedOrders(OrderRepository repo) {
        return args -> repo.deleteAll()
                .thenMany(Flux.just(
                        new Order(null, "AAPL", "BUY",  new BigDecimal("189.50"), 10, LocalDateTime.now()),
                        new Order(null, "GOOG", "SELL", new BigDecimal("142.10"),  5, LocalDateTime.now()),
                        new Order(null, "MSFT", "BUY",  new BigDecimal("410.25"),  7, LocalDateTime.now()),
                        new Order(null, "TSLA", "SELL", new BigDecimal("245.00"),  3, LocalDateTime.now()),
                        new Order(null, "AMZN", "BUY",  new BigDecimal("178.90"), 12, LocalDateTime.now())
                ).flatMap(repo::save))
                .blockLast();
    }
}

/* ------------------------------------------------------------------ */
/*                         Domain / Entity                             */
/* ------------------------------------------------------------------ */

@Table("orders")
class Order {
    @Id
    private Long id;
    private String symbol;
    private String side;
    private BigDecimal price;
    private Integer quantity;
    private LocalDateTime createdAt;

    public Order() { }

    public Order(Long id, String symbol, String side, BigDecimal price,
                 Integer quantity, LocalDateTime createdAt) {
        this.id = id;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

/* ------------------------------------------------------------------ */
/*                            Repository                               */
/* ------------------------------------------------------------------ */

interface OrderRepository extends R2dbcRepository<Order, Long> {
    Flux<Order> findBySymbol(String symbol);
}

/* ------------------------------------------------------------------ */
/*                          REST / JSON API                            */
/* ------------------------------------------------------------------ */

@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final OrderRepository repo;

    OrderController(OrderRepository repo) {
        this.repo = repo;
    }

    /** All orders - Flux. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Order> all() {
        return repo.findAll();
    }

    /** Single order - Mono. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Order> byId(@PathVariable Long id) {
        return repo.findById(id);
    }

    /** Orders by ticker - Flux. */
    @GetMapping(value = "/symbol/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Order> bySymbol(@PathVariable String symbol) {
        return repo.findBySymbol(symbol.toUpperCase());
    }

    /** Create an order - Mono. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Order> create(@RequestBody Order order) {
        order.setId(null);
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(LocalDateTime.now());
        }
        return repo.save(order);
    }
}

/* ------------------------------------------------------------------ */
/*        HTML view showing the contents of H2                         */
/* ------------------------------------------------------------------ */

@RestController
class OrderView {

    private final OrderRepository repo;

    OrderView(OrderRepository repo) {
        this.repo = repo;
    }

    @GetMapping(value = "/orders", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> page() {
        return repo.findAll()
                .map(o -> "<tr>"
                        + "<td>" + o.getId() + "</td>"
                        + "<td>" + o.getSymbol() + "</td>"
                        + "<td>" + o.getSide() + "</td>"
                        + "<td>" + o.getPrice() + "</td>"
                        + "<td>" + o.getQuantity() + "</td>"
                        + "<td>" + o.getCreatedAt() + "</td>"
                        + "</tr>")
                .collectList()
                .map(rows -> "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                        + "<title>H2 :: orders</title>"
                        + "<style>body{font-family:sans-serif;margin:2rem}"
                        + "table{border-collapse:collapse}td,th{border:1px solid #999;padding:6px 12px}"
                        + "th{background:#eee}</style></head><body>"
                        + "<h1>H2 :: orders</h1>"
                        + "<p>Total: " + rows.size() + "</p>"
                        + "<table><tr><th>id</th><th>symbol</th><th>side</th>"
                        + "<th>price</th><th>qty</th><th>createdAt</th></tr>"
                        + String.join("", rows)
                        + "</table></body></html>");
    }
}
