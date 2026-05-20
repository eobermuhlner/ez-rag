---
title: Machine Learning Algorithms Reference
topic: technology
category: machine-learning
---

# Machine Learning Algorithms Reference

## Supervised Learning Algorithms

Supervised learning trains models on labeled data to learn a mapping from inputs to outputs
[Bishop, 2006].

| Algorithm           | Type           | Complexity     | Interpretable | Best For                          |
|---------------------|----------------|----------------|---------------|-----------------------------------|
| Linear Regression   | Regression     | O(n·d)         | High          | Continuous output prediction      |
| Logistic Regression | Classification | O(n·d)         | High          | Binary classification             |
| Decision Tree       | Both           | O(n·d·log n)   | High          | Rule-based decisions              |
| Random Forest       | Both           | O(n·d·t)       | Medium        | Tabular data, feature importance  |
| Gradient Boosting   | Both           | O(n·d·t)       | Low           | High-accuracy tabular tasks       |
| SVM                 | Both           | O(n²–n³)       | Low           | Small datasets, high-dimensional  |
| Neural Network      | Both           | O(n·d·l)       | Very Low      | Images, text, sequences           |

*n = samples, d = features, t = trees/estimators, l = layers*

## Unsupervised Learning Algorithms

Unsupervised learning discovers hidden structure in unlabeled data [Hastie et al., 2009].

| Algorithm   | Output       | Scalability | Hyperparameters   | Best For                     |
|-------------|--------------|-------------|-------------------|------------------------------|
| K-Means     | Clusters     | High        | k (num clusters)  | Spherical, compact clusters  |
| DBSCAN      | Clusters     | Medium      | ε, min_samples    | Arbitrary-shaped clusters    |
| PCA         | Components   | High        | n_components      | Dimensionality reduction     |
| t-SNE       | 2D/3D layout | Low         | perplexity        | Visualization                |
| Autoencoder | Latent space | Medium      | Architecture      | Anomaly detection            |
| LDA         | Topics       | Medium      | num_topics        | Topic modeling in text       |

## Training Pipeline

A standard ML training pipeline consists of the following phases:

1. **Data Preparation**
   - Collection
     - Structured sources: databases, CSV files, APIs
     - Unstructured sources: web scraping, PDFs, images
   - Cleaning
     - Remove or impute missing values
     - Detect and handle outliers (IQR method or z-score > 3)
     - Deduplicate records
   - Splitting
     - Training: 70–80% of data
     - Validation: 10–15% of data
     - Test: 10–15% of data (held out until final evaluation)
2. **Feature Engineering**
   - Numerical: scaling (StandardScaler, MinMaxScaler), binning
   - Categorical: one-hot encoding, target encoding, label encoding
   - Text: TF-IDF, word embeddings, tokenization
   - Temporal: lag features, rolling statistics, seasonal decomposition
3. **Model Training**
   - Select algorithm based on problem type and data characteristics
   - Tune hyperparameters using cross-validation (k-fold, stratified k-fold)
   - Monitor training loss and validation loss for overfitting signs
4. **Evaluation**
   - Classification metrics: accuracy, precision, recall, F1-score, AUC-ROC
   - Regression metrics: MAE, RMSE, R²
   - Always evaluate on the held-out test set

## Evaluation Metrics for Classification

| Metric   | Formula                       | Range | Notes                             |
|----------|-------------------------------|-------|-----------------------------------|
| Accuracy | (TP + TN) / Total             | 0–1   | Misleading on imbalanced data     |
| Precision| TP / (TP + FP)                | 0–1   | Minimizes false positives         |
| Recall   | TP / (TP + FN)                | 0–1   | Minimizes false negatives         |
| F1-Score | 2 · (P · R) / (P + R)         | 0–1   | Harmonic mean of precision/recall |
| AUC-ROC  | Area under the ROC curve      | 0.5–1 | Threshold-independent             |

> **Note:** For severely imbalanced datasets (e.g., fraud detection where fewer than 0.1% of
> cases are positive), prefer **AUC-ROC** or **F1-Score** over accuracy — accuracy can be
> misleadingly high when the model simply predicts the majority class every time
> [Japkowicz & Stephen, 2002].

## References

- [Bishop, 2006] C.M. Bishop, *Pattern Recognition and Machine Learning*, Springer, 2006.
- [Hastie et al., 2009] T. Hastie, R. Tibshirani, J. Friedman, *The Elements of Statistical Learning*, 2nd ed., Springer, 2009.
- [Japkowicz & Stephen, 2002] N. Japkowicz, S. Stephen, "The Class Imbalance Problem: A Systematic Study," *Intelligent Data Analysis*, 2002.
