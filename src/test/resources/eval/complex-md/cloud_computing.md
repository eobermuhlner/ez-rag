---
title: Cloud Computing Services Reference
topic: technology
category: cloud
---

# Cloud Computing Services Reference

## Service Models

Cloud computing delivers IT resources over the internet under three primary service models
[NIST, 2011]:

| Model | Full Name                   | Provider Manages              | Customer Manages        | Examples                              |
|-------|-----------------------------|-------------------------------|-------------------------|---------------------------------------|
| IaaS  | Infrastructure as a Service | Hardware, networking, VMs     | OS, runtime, apps, data | AWS EC2, Azure VMs, GCP Compute       |
| PaaS  | Platform as a Service       | IaaS + OS + runtime           | Apps, data              | Heroku, App Engine, Azure App Service |
| SaaS  | Software as a Service       | Everything                    | Configuration only      | Gmail, Salesforce, Office 365         |
| FaaS  | Functions as a Service      | IaaS + OS + runtime + scaling | Function code only      | AWS Lambda, Azure Functions           |

## Cloud Providers

The three largest public cloud providers hold over 65% of the global cloud market [Gartner, 2024]:

| Provider           | Compute       | Container Service | ML Platform  | Managed Database      |
|--------------------|---------------|-------------------|--------------|-----------------------|
| AWS                | EC2           | EKS               | SageMaker    | RDS, DynamoDB         |
| Microsoft Azure    | Azure VMs     | AKS               | Azure ML     | Azure SQL, Cosmos DB  |
| Google Cloud (GCP) | Compute Engine| GKE               | Vertex AI    | Cloud SQL, Spanner    |

## Cloud Database Services

Cloud providers offer managed database services that abstract away server provisioning and patching:

- **Relational (SQL)**
  - AWS RDS: managed PostgreSQL, MySQL, Oracle, and SQL Server instances
  - Azure SQL Database: fully managed SQL Server engine with built-in high availability
  - Google Cloud SQL: managed MySQL and PostgreSQL; automated backups and failover
  - All enforce ACID transactions and support read replicas for horizontal read scaling
- **NoSQL / Document / Key-Value**
  - AWS DynamoDB: serverless key-value and document store with single-digit millisecond latency
  - Azure Cosmos DB: multi-model globally distributed store with tunable consistency levels
  - Google Firestore: document-oriented NoSQL optimised for mobile and web applications
- **Cache Services**
  - AWS ElastiCache: managed Redis and Memcached for in-memory caching workloads
  - Azure Cache for Redis: fully managed Redis with optional geo-replication
- **Analytical / Data Warehouse**
  - AWS Redshift: columnar storage, petabyte-scale OLAP queries
  - Google BigQuery: serverless analytics engine; separates compute and storage
  - Azure Synapse Analytics: unified analytics with SQL pools and Apache Spark

The choice between SQL and NoSQL managed services mirrors the considerations for self-hosted
databases: ACID compliance and relational integrity favour managed SQL; horizontal scalability
and flexible document schemas favour managed NoSQL.

## Machine Learning Platforms

Cloud ML platforms provide managed infrastructure for training and serving models at scale:

| Feature              | AWS SageMaker             | Azure ML Studio        | Google Vertex AI           |
|----------------------|---------------------------|------------------------|----------------------------|
| AutoML               | Autopilot                 | Automated ML           | AutoML                     |
| Notebook environment | Studio Notebooks          | Compute Instances      | Workbench                  |
| Training job types   | Spot, on-demand           | Compute Clusters       | Custom/pre-built jobs      |
| Model registry       | SageMaker Model Registry  | Azure Model Registry   | Vertex Model Registry      |
| Serving              | Real-time endpoints       | ACI/AKS endpoints      | Online/batch prediction    |
| MLOps pipelines      | SageMaker Pipelines       | Azure ML Pipelines     | Vertex Pipelines           |

Training large models in the cloud follows the same general pipeline as local training —
data preparation, feature engineering, model selection, hyperparameter tuning, and evaluation
— but distributes compute across GPU clusters and persists artefacts in object storage
(S3, Azure Blob, GCS).

## Cloud Networking

Cloud networking connects compute resources, storage, and external users:

- **Virtual Private Cloud (VPC)**
  - Isolated network segment within the provider's infrastructure
  - Public and private subnets segment workloads by security zone
  - Route tables and NAT gateways control ingress and egress traffic
- **Load Balancers**
  - Layer 4 (transport): routes TCP/UDP traffic by IP and port — AWS NLB, Azure Load Balancer
  - Layer 7 (application): routes HTTP/HTTPS traffic by path or header — AWS ALB, Azure Application Gateway
- **Content Delivery Networks (CDN)**
  - Cache static assets at edge locations close to end users
  - Reduce round-trip latency and offload origin servers
  - Examples: AWS CloudFront, Azure CDN, Google Cloud CDN
- **DNS Services**
  - AWS Route 53, Azure DNS, Google Cloud DNS
  - Support latency-based, geolocation, and failover routing policies

## Deployment Models

| Model       | Description                                    | Infrastructure Control  |
|-------------|------------------------------------------------|-------------------------|
| Public      | Resources shared across multiple tenants       | Cloud provider          |
| Private     | Dedicated infrastructure for one organisation  | Organisation or provider|
| Hybrid      | Mix of on-premises and public cloud resources  | Shared                  |
| Multi-cloud | Services distributed across two or more clouds | Organisation            |

## References

- [NIST, 2011] P. Mell, T. Grance, "The NIST Definition of Cloud Computing," *NIST SP 800-145*, 2011.
- [Gartner, 2024] Gartner, *Magic Quadrant for Cloud Infrastructure and Platform Services*, 2024.
