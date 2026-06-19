package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.awt.Rectangle
import java.io.File

/**
 * Generates binary eval corpus fixtures (DOCX, PPTX) for the chunking eval scenarios.
 * These files are written to src/test/resources/eval/ and committed as static fixtures.
 * Run via EvalCorpusBinaryGeneratorTest (a standard @Test, not tagged eval).
 */
object EvalCorpusBinaryGenerator {

    private val evalDir = File("src/test/resources/eval")

    // ── DOCX ────────────────────────────────────────────────────────────────────

    fun createDocxFixture(file: File) {
        writeIfChanged(file) { baos ->
            XWPFDocument().use { doc ->
                addHeading1(doc, "Distributed Systems: An Overview")
                addParagraph(
                    doc,
                    "Distributed systems form the backbone of modern computing infrastructure, " +
                    "enabling applications to scale beyond the limits of any single machine. " +
                    "A distributed system is a collection of independent nodes that cooperate to " +
                    "present a unified service to end users. These nodes communicate over a network, " +
                    "which introduces latency, packet loss, and the possibility of partial failure. " +
                    "The fundamental difficulty of distributed systems was articulated by Leslie Lamport, " +
                    "who famously observed that a distributed system is one in which the failure of a " +
                    "computer you did not even know existed can render your own computer unusable."
                )
                addParagraph(
                    doc,
                    "The theoretical foundation for understanding distributed systems rests on the CAP theorem, " +
                    "formulated by Eric Brewer in 2000 and later proved by Seth Gilbert and Nancy Lynch. " +
                    "The theorem states that any distributed data store can guarantee at most two of three " +
                    "properties: consistency, availability, and partition tolerance. Consistency means that " +
                    "every read receives the most recent write or an error. Availability means that every " +
                    "request receives a non-error response, though not necessarily the most recent data. " +
                    "Partition tolerance means the system continues to operate even when network partitions " +
                    "split the cluster into isolated subsets. Systems such as Apache Zookeeper and Google " +
                    "Chubby sacrifice availability to maintain strong consistency, while systems such as " +
                    "Amazon Dynamo and Apache Cassandra sacrifice consistency to maintain availability."
                )
                addParagraph(
                    doc,
                    "The practical implementation of a distributed system requires careful consideration " +
                    "of replication strategies, which determine how data is copied across nodes to ensure " +
                    "durability and availability. In a leader-based replication model, one node is designated " +
                    "the primary and handles all write operations. Raft and Paxos are the two most widely " +
                    "deployed consensus algorithms for leader election and log replication."
                )

                addHeading2(doc, "Concurrency Models in Distributed Computing")
                addParagraph(
                    doc,
                    "Concurrency is a defining characteristic of distributed systems: multiple nodes execute " +
                    "simultaneously, and their operations must be carefully coordinated to avoid anomalies. " +
                    "The classic anomalies include dirty reads, phantom reads, non-repeatable reads, and write skew. " +
                    "The ANSI SQL standard defines four isolation levels in ascending order of strictness, " +
                    "with serialisable isolation being the strongest guarantee."
                )
                addParagraph(
                    doc,
                    "The actor model, pioneered by Carl Hewitt in 1973 and popularised by the Erlang " +
                    "programming language, offers an alternative approach to concurrency that avoids shared " +
                    "mutable state entirely. In the actor model, the fundamental unit of computation is an " +
                    "actor: a lightweight stateful entity that processes messages from its mailbox one at a time. " +
                    "Actors communicate exclusively by passing immutable messages, and each actor's state is " +
                    "private and inaccessible to other actors."
                )
                addParagraph(
                    doc,
                    "Lock-free and wait-free algorithms represent the most demanding end of the concurrency " +
                    "spectrum. A lock-free algorithm guarantees that at least one thread makes progress in a " +
                    "finite number of steps. The ABA problem is the canonical pitfall of lock-free programming: " +
                    "a compare-and-swap reads a value A, which is then changed to B and back to A by another " +
                    "thread, causing the CAS to succeed even though the state has changed in the interim. " +
                    "Hazard pointers and epoch-based reclamation are the standard techniques for safe memory " +
                    "reclamation in lock-free data structures."
                )

                addHeading2(doc, "Fault Tolerance and Recovery")
                addParagraph(
                    doc,
                    "Fault tolerance is the capacity of a system to continue operating correctly in the " +
                    "presence of failures. In distributed systems, failures are not exceptional events but a " +
                    "routine aspect of operation at scale. Netflix has operationalised this reality through its " +
                    "Chaos Engineering practice, deliberately injecting failures into production systems using " +
                    "tools such as Chaos Monkey and Chaos Gorilla to verify that the system degrades gracefully."
                )
                addParagraph(
                    doc,
                    "The circuit breaker pattern, made famous by Michael Nygard in the book Release It, " +
                    "is one of the most widely adopted fault tolerance techniques in distributed systems. " +
                    "A circuit breaker wraps calls to an external service and monitors the rate of failures. " +
                    "When the failure rate exceeds a configured threshold, the circuit breaker transitions " +
                    "from the closed state to the open state and begins rejecting calls immediately without " +
                    "attempting to contact the failing service."
                )
                addParagraph(
                    doc,
                    "Bulkhead isolation takes its metaphor from the watertight compartments of a ship's hull: " +
                    "by isolating resources used to call different downstream services into separate thread pools " +
                    "or connection pools, the failure of one service cannot exhaust resources needed to call others. " +
                    "Distributed tracing systems such as Jaeger, Zipkin, and AWS X-Ray propagate a trace context " +
                    "consisting of a trace identifier and a span identifier in request headers as work flows " +
                    "between services."
                )

                doc.write(baos)
            }
        }
    }

    private fun addHeading1(doc: XWPFDocument, text: String) {
        val para = doc.createParagraph()
        para.style = "Heading1"
        para.createRun().setText(text)
    }

    private fun addHeading2(doc: XWPFDocument, text: String) {
        val para = doc.createParagraph()
        para.style = "Heading2"
        para.createRun().setText(text)
    }

    private fun addParagraph(doc: XWPFDocument, text: String) {
        val para = doc.createParagraph()
        para.createRun().setText(text)
    }

    // ── PPTX ────────────────────────────────────────────────────────────────────

    fun createPptxFixture(file: File) {
        writeIfChanged(file) { baos ->
            XMLSlideShow().use { ppt ->

                // Slide 1: Introduction — distributed systems overview and CAP theorem
                val slide1 = ppt.createSlide()
                val title1 = slide1.createTextBox()
                title1.text = "Distributed Systems Overview"
                title1.setAnchor(Rectangle(50, 50, 600, 80))

                val body1 = slide1.createTextBox()
                body1.text = "Distributed systems form the backbone of modern computing infrastructure, " +
                    "enabling applications to scale beyond the limits of any single machine. " +
                    "A distributed system is a collection of independent nodes that cooperate to present " +
                    "a unified service to end users. The CAP theorem states that any distributed data store " +
                    "can guarantee at most two of three properties: consistency, availability, and partition " +
                    "tolerance. Raft and Paxos are the two most widely deployed consensus algorithms for " +
                    "leader election and log replication in distributed systems."
                body1.setAnchor(Rectangle(50, 150, 600, 350))

                // Slide 2: Concurrency Models — actor model and lock-free algorithms
                val slide2 = ppt.createSlide()
                val title2 = slide2.createTextBox()
                title2.text = "Concurrency Models"
                title2.setAnchor(Rectangle(50, 50, 600, 80))

                val body2 = slide2.createTextBox()
                body2.text = "Concurrency is a defining characteristic of distributed systems. " +
                    "The actor model, pioneered by Carl Hewitt in 1973, avoids shared mutable state entirely. " +
                    "In the actor model, actors communicate exclusively by passing immutable messages, and " +
                    "each actor's state is private and inaccessible to other actors. " +
                    "The ABA problem is the canonical pitfall of lock-free programming: a compare-and-swap " +
                    "reads a value A, which is then changed to B and back to A by another thread, causing " +
                    "the CAS to succeed even though the state has changed in the interim."
                body2.setAnchor(Rectangle(50, 150, 600, 350))

                // Slide 3: Fault Tolerance — circuit breaker and bulkhead isolation
                val slide3 = ppt.createSlide()
                val title3 = slide3.createTextBox()
                title3.text = "Fault Tolerance and Recovery"
                title3.setAnchor(Rectangle(50, 50, 600, 80))

                val body3 = slide3.createTextBox()
                body3.text = "Fault tolerance is the capacity of a system to continue operating correctly " +
                    "in the presence of failures. Netflix uses Chaos Engineering with tools such as Chaos " +
                    "Monkey to verify that the system degrades gracefully. The circuit breaker pattern " +
                    "transitions from the closed state to the open state and begins rejecting calls " +
                    "immediately without attempting to contact the failing service when failures exceed the " +
                    "threshold. Bulkhead isolation separates resources into distinct thread pools so that " +
                    "failure of one downstream service cannot exhaust resources needed to call other services. " +
                    "Distributed tracing systems such as Jaeger, Zipkin, and AWS X-Ray propagate a trace " +
                    "context in request headers as work flows between services."
                body3.setAnchor(Rectangle(50, 150, 600, 350))

                ppt.write(baos)
            }
        }
    }

    // ── XLSX ────────────────────────────────────────────────────────────────────

    fun createXlsxFixture(file: File) {
        writeIfChanged(file) { baos ->
            XSSFWorkbook().use { wb ->
                val sheet = wb.createSheet("Products")

                // Header row
                val headerRow = sheet.createRow(0)
                listOf("name", "category", "price", "description").forEachIndexed { i, v ->
                    headerRow.createCell(i).setCellValue(v)
                }

                // Data rows — same product catalog as the other structured-format scenarios
                val products = listOf(
                    listOf("Titanium Water Bottle", "Outdoor", "34.99",
                        "A lightweight titanium water bottle designed for hikers and trail runners. Holds 750 ml and keeps liquids cold for eight hours."),
                    listOf("Solar LED Lantern", "Camping", "49.99",
                        "Rechargeable solar-powered LED lantern with a built-in USB port. Provides up to twelve hours of continuous illumination per charge."),
                    listOf("Merino Wool Beanie", "Apparel", "22.50",
                        "Soft merino wool beanie suitable for cold weather activities. Machine washable and available in six neutral colours."),
                    listOf("Carbon Fibre Trekking Poles", "Outdoor", "89.00",
                        "Collapsible carbon fibre trekking poles with ergonomic cork handles. Adjustable from 60 cm to 130 cm and weighing only 180 grams each."),
                    listOf("Waterproof Trail Shoes", "Footwear", "129.95",
                        "Gore-Tex waterproof trail shoes with a Vibram outsole for maximum grip on wet and rocky terrain. Available in sizes 36 to 48."),
                    listOf("Insulated Sleeping Bag", "Camping", "179.00",
                        "Three-season sleeping bag rated to minus ten degrees Celsius. Filled with 650-fill-power down and weighing 900 grams."),
                    listOf("Portable Water Filter", "Survival", "44.95",
                        "Hollow-fibre membrane filter that removes 99.9999 percent of bacteria and protozoa. Filters up to 100 000 litres before replacement."),
                    listOf("Headlamp Pro 500", "Lighting", "59.99",
                        "Rechargeable headlamp delivering 500 lumens with a 100-metre beam. Features red night-vision mode and IPX6 waterproof rating."),
                    listOf("Packable Rain Jacket", "Apparel", "99.00",
                        "Ultra-packable 2.5-layer rain jacket that folds into its own chest pocket. Seam-sealed and weighing only 220 grams."),
                    listOf("Satellite Communicator", "Safety", "349.00",
                        "Two-way satellite communicator with GPS tracking and SOS functionality. Compatible with all major satellite networks and weather forecast subscriptions."),
                    listOf("Compression Dry Bag", "Storage", "18.75",
                        "Roll-top compression dry bag in 10-litre and 20-litre sizes. Constructed from 210D ripstop nylon with welded seams for complete waterproofing."),
                    listOf("Multi-Tool Knife", "Tools", "74.50",
                        "Stainless steel multi-tool knife with fourteen functions including pliers, wire cutters, a file, and a bottle opener. Comes with a ballistic nylon sheath."),
                )

                products.forEachIndexed { rowIndex, product ->
                    val row = sheet.createRow(rowIndex + 1)
                    product.forEachIndexed { colIndex, value ->
                        row.createCell(colIndex).setCellValue(value)
                    }
                }

                wb.write(baos)
            }
        }
    }

    // ── Entry point ─────────────────────────────────────────────────────────────

    fun generateAll() {
        createDocxFixture(File(evalDir, "chunking-docx/distributed_systems.docx").also { it.parentFile.mkdirs() })
        createPptxFixture(File(evalDir, "chunking-pptx/distributed_systems.pptx").also { it.parentFile.mkdirs() })
        createXlsxFixture(File(evalDir, "chunking-xlsx/products.xlsx").also { it.parentFile.mkdirs() })
    }
}
