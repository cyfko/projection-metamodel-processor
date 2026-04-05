# Guide de Migration du Processor — jpa-projection-metamodel v3.0.0

**Audience** : Développeur du module annotation processor  
**Dépendance** : `io.github.cyfko:jpa-projection-metamodel:3.0.0-SNAPSHOT`  
**Spec de référence** : `projection-spec` v3.0.0

---

## 1. Vue d'Ensemble des Changements

Le module `jpa-projection-metamodel` v3.0.0 introduit une modélisation runtime de la **Layer 3 (Exposure)** de `projection-spec`, et enrichit `DirectMapping` pour supporter le **Composed Criterion Inheritance**.

```
projection-spec v3.0.0 (annotations SOURCE)
        │
        ├─[INCHANGÉ] @Projection, @Projected(from)
        ├─[NOUVEAU]  @Projected(as, cycleBreak)
        ├─[NOUVEAU]  @ExposedAs, @Exposure, StandardOp
        └─[NOUVEAU]  @Computed(dependsOn = {"path:REDUCER"})  ← syntaxe inline
                │
                ▼
jpa-projection-metamodel v3.0.0 (records RUNTIME)
        │
        ├─[MODIFIÉ]  DirectMapping      (+logicalPrefix, +cycleBreak)
        ├─[MODIFIÉ]  ProjectionMetadata (+exposedCriteria, +exposure)
        ├─[NOUVEAU]  ExposedCriterion
        ├─[NOUVEAU]  ExposureMetadata
        └─[INCHANGÉ] ComputedField, ReducerMapping  ← le processor parse "path:REDUCER"
                │
                ▼
jpa-metamodel-processor (LE PROCESSOR — CE GUIDE)
```

---

## 2. Changements sur les Constructeurs

### 2.1 `DirectMapping` — 6 composants (était 4)

```java
// Avant (v2.x)
new DirectMapping(
    "email",              // dtoField
    "email",              // entityField
    String.class,         // dtoFieldType
    Optional.empty()      // collection
);

// Après (v3.0.0) — mapping scalaire simple
new DirectMapping(
    "email",              // dtoField
    "email",              // entityField
    String.class,         // dtoFieldType
    Optional.empty(),     // collection
    Optional.empty(),     // logicalPrefix  ← NOUVEAU
    false                 // cycleBreak     ← NOUVEAU
);

// Après (v3.0.0) — mapping vers un type @Projection
new DirectMapping(
    "sourceSite",         // dtoField
    "sourceSite",         // entityField
    SiteDTO.class,        // dtoFieldType (le type @Projection)
    Optional.empty(),     // collection
    Optional.of("SOURCE_SITE"),  // logicalPrefix  ← valeur de @Projected(as)
    false                        // cycleBreak     ← valeur de @Projected(cycleBreak)
);
```

> **Note** : Le constructeur à 4 arguments reste disponible (convenience) et délègue au canonique avec `Optional.empty()` et `false`. Si le processor génère du code qui instancie directement le constructeur canonique, il doit ajouter les 2 nouveaux arguments.

#### Règles de résolution du `logicalPrefix`

| Condition | `logicalPrefix` | `cycleBreak` |
|-----------|-----------------|--------------|
| Le type de retour **n'est pas** un `@Projection` (scalaire) | `Optional.empty()` | `false` |
| Le type de retour **est** un `@Projection`, `as` non spécifié | `Optional.of(toScreamingSnake(methodName))` | valeur de `@Projected(cycleBreak)` |
| Le type de retour **est** un `@Projection`, `as` spécifié | `Optional.of(asValue)` | valeur de `@Projected(cycleBreak)` |
| `cycleBreak = true` | Doit quand même être fourni | `true` |

#### Conversion `SCREAMING_SNAKE_CASE` du nom de méthode

```
getSourceSite  → SOURCE_SITE     (strip "get", camelCase → SCREAMING_SNAKE)
sourceSite     → SOURCE_SITE     (si pas de préfixe "get")
getUnitModel   → UNIT_MODEL
```

#### Validations imposées par le record

Le constructeur de `DirectMapping` lève une `IllegalArgumentException` si `logicalPrefix` :
- contient `__` (réservé comme séparateur de composition)
- commence par `_`
- finit par `_`
- est blank (mais présent)

Le processor n'a pas à valider ces règles lui-même — le record les impose.

---

### 2.2 `ProjectionMetadata` — 6 composants (était 4)

```java
// Avant (v2.x)
new ProjectionMetadata(
    User.class,           // entityClass
    directMappings,       // DirectMapping[]
    computedFields,       // ComputedField[]
    providers             // ComputationProvider[]
);

// Après (v3.0.0)
new ProjectionMetadata(
    User.class,           // entityClass
    directMappings,       // DirectMapping[]
    computedFields,       // ComputedField[]
    providers,            // ComputationProvider[]
    exposedCriteria,      // ExposedCriterion[]    ← NOUVEAU
    exposureMetadata      // ExposureMetadata       ← NOUVEAU (nullable)
);
```

> **Note** : Le constructeur à 4 arguments reste disponible et délègue avec `new ExposedCriterion[]{}` et `null`.

---

### 2.3 `ComputedField` — INCHANGÉ

Le record `ComputedField` et son `ReducerMapping(int dependencyIndex, String reducer)` sont **strictement inchangés**. 

La spec v3.0.0 utilise la syntaxe inline dans `@Computed(dependsOn = {"orders.total:SUM"})`, mais c'est au **processor** de parser cette syntaxe :

```java
// Annotation source (spec v3.0.0)
@Computed(dependsOn = {"id", "orders.total:SUM", "refunds.amount:SUM"})

// Ce que le processor doit générer :
String[] dependencies = {"id", "orders.total", "refunds.amount"};  // chemins propres
ReducerMapping[] reducers = {
    new ReducerMapping(1, "SUM"),   // index 1 → "orders.total"
    new ReducerMapping(2, "SUM")    // index 2 → "refunds.amount"
};
new ComputedField("revenu", dependencies, reducers, computedByRef, transformerRef);
```

**Algorithme de parsing** :
```
pour chaque entrée dans dependsOn :
    si contient ':' :
        path    = substring avant ':'
        reducer = substring après ':'
        ajouter path à dependencies[]
        ajouter ReducerMapping(index, reducer) à reducers[]
    sinon :
        ajouter l'entrée telle quelle à dependencies[]
```

---

## 3. Nouveaux Types à Instancier

### 3.1 `ExposedCriterion`

Le processor doit instancier un `ExposedCriterion` pour chaque critère queryable — qu'il soit déclaré directement ou hérité par composition.

```java
import io.github.cyfko.jpametamodel.api.ExposedCriterion;

new ExposedCriterion(
    ref,            // String   — "NAME" ou "SOURCE_SITE__SITE_NAME"
    sourcePath,     // String   — "name" ou "sourceSite.name"
    operators,      // String[] — {"EQ", "MATCHES"}
    exposed,        // boolean  — valeur de @ExposedAs(exposed), défaut true
    composed        // boolean  — false si direct, true si hérité
);
```

#### Critère direct (déclaré via `@ExposedAs` sur un champ scalaire)

```java
// Source :
// @ExposedAs(value = "SITE_NAME", operators = {StandardOp.EQ, StandardOp.MATCHES})
// String getName();  ← dans SiteDTO, @Projection(from = Site.class)

new ExposedCriterion(
    "SITE_NAME",                    // ref = @ExposedAs(value)
    "name",                         // sourcePath = entityField du DirectMapping correspondant
    new String[]{"EQ", "MATCHES"},  // operators = @ExposedAs(operators) convertis en String
    true,                           // exposed = @ExposedAs(exposed), défaut true
    false                           // composed = false (déclaré directement)
);
```

#### Critère composé (hérité d'un `@Projection` imbriqué)

```java
// Source :
// ConnectionDTO a @Projected(from = "sourceSite", as = "SOURCE_SITE") SiteDTO getSourceSite();
// SiteDTO expose @ExposedAs(value = "SITE_NAME") sur getName()
// Le DirectMapping de getSourceSite() a entityField = "sourceSite"

new ExposedCriterion(
    "SOURCE_SITE__SITE_NAME",       // ref = prefix + "__" + critère hérité
    "sourceSite.name",              // sourcePath = entityField du parent + "." + sourcePath du critère enfant
    new String[]{"EQ", "MATCHES"},  // operators = hérités tels quels
    true,                           // exposed = hérité tel quel
    true                            // composed = true
);
```

#### Critère composé récursif (2+ niveaux)

```java
// SiteDTO expose LOCALITY → LocalityDTO expose NAME
// ConnectionDTO expose SOURCE_SITE → SiteDTO

new ExposedCriterion(
    "SOURCE_SITE__LOCALITY__NAME",          // ref = préfixes chaînés
    "sourceSite.locality.name",             // sourcePath = chemins chaînés
    new String[]{"EQ"},
    true,
    true
);
```

---

### 3.2 `ExposureMetadata`

Le processor doit instancier un `ExposureMetadata` si le DTO porte `@Exposure`.

```java
import io.github.cyfko.jpametamodel.api.ExposureMetadata;

// Source :
// @Exposure(value = "connections", namespace = "api", strategy = Strategy.WINDOWED)

new ExposureMetadata(
    "connections",    // value     = @Exposure(value)
    "api",            // namespace = @Exposure(namespace), "" si non spécifié
    "WINDOWED"        // strategy  = @Exposure(strategy).name()
);
```

Si `@Exposure` est absent → passer `null` comme dernier argument de `ProjectionMetadata`.

---

## 4. Algorithme de Collecte des Critères Composés

### 4.1 Pseudocode

```
function collectCriteria(projectionType, parentPrefix, parentEntityPath, visited) → List<ExposedCriterion>:
    
    si projectionType ∈ visited :
        ERREUR DE COMPILATION : cycle détecté
        return []
    
    visited = visited ∪ {projectionType}
    result = []
    
    pour chaque méthode M de projectionType :
        
        si M porte @ExposedAs :
            ref = M.@ExposedAs.value
            
            // Validation : ref ne doit pas contenir "__"
            si ref contient "__" :
                ERREUR DE COMPILATION
            
            entityPath = résoudre M → chemin entité (via @Projected(from) ou nom de méthode)
            
            si parentPrefix est vide :
                // Critère direct
                result.add(ExposedCriterion(ref, entityPath, operators, exposed, false))
            sinon :
                // Critère composé
                composedRef = parentPrefix + "__" + ref
                composedPath = parentEntityPath + "." + entityPath
                result.add(ExposedCriterion(composedRef, composedPath, operators, exposed, true))
        
        si M retourne un type @Projection (non-scalaire) :
            
            si M porte @ExposedAs :
                ERREUR DE COMPILATION : @ExposedAs interdit sur type non-scalaire
            
            cycleBreak = M.@Projected.cycleBreak
            si cycleBreak :
                continue  // ne pas récurser
            
            prefix = résoudre préfixe logique (M.@Projected.as ou SCREAMING_SNAKE(M.name))
            
            // Vérifier conflit de préfixe dans le DTO courant
            si prefix déjà utilisé dans ce DTO :
                ERREUR DE COMPILATION : conflit de préfixe
            
            childEntityPath = M.@Projected.from ou nom de méthode
            
            fullPrefix = (parentPrefix vide) ? prefix : parentPrefix + "__" + prefix
            fullEntityPath = (parentEntityPath vide) ? childEntityPath : parentEntityPath + "." + childEntityPath
            
            // Récursion
            childCriteria = collectCriteria(M.returnType, fullPrefix, fullEntityPath, visited)
            result.addAll(childCriteria)
    
    visited = visited \ {projectionType}  // backtrack pour DFS
    return result
```

### 4.2 Point d'entrée

```
pour chaque DTO annoté @Projection :
    allCriteria = collectCriteria(DTO, "", "", {})
    // allCriteria contient à la fois les critères directs ET composés
```

### 4.3 Détection de cycles

La détection de cycles se fait par un `Set<TypeMirror>` (ou `Set<String>` des noms qualifiés) maintenu pendant la récursion DFS. Si le type courant est déjà dans le `visited`, le processor lève :

```
ERROR: Bidirectional projection cycle without cycleBreak:
  ConnectionDTO → SiteDTO → OrganisationDTO → ConnectionDTO

  Offending field: OrganisationDTO#getConnections()

  Fix: Annotate the field with @Projected(cycleBreak = true)
       to exclude it from criterion inheritance while keeping it
       available for projection (read).
```

### 4.4 Détection de conflits de préfixe

Deux champs du même DTO ne peuvent pas générer le même préfixe logique :

```
ERROR: Duplicate composed criterion prefix "SOURCE_SITE" in ConnectionDTO.
  Defined by: getSourceSite(), getBackupSourceSite()
  Fix: Provide distinct values for the 'as' attribute.
```

### 4.5 `@ExposedAs` interdit sur type non-scalaire

```
ERROR: @ExposedAs is not allowed on ConnectionDTO#getSourceSite().
  Reason: SiteDTO is a @Projection type, not a scalar-compatible type.
  Fix: Remove @ExposedAs. The queryable properties of SiteDTO
       are inherited automatically under the prefix "SOURCE_SITE".
```

---

## 5. Validation de `@ExposedAs(value)`

Le processor doit valider que `@ExposedAs(value)` respecte le format `SCREAMING_SNAKE_CASE` strict :

**Pattern** : `[A-Z][A-Z0-9]*(_[A-Z][A-Z0-9]*)*`

| Règle | Exemple invalide | Message |
|-------|-----------------|---------|
| Pas de `__` | `"SITE__NAME"` | Réservé comme séparateur de composition |
| Pas de `_` en début | `"_SITE_NAME"` | Format SCREAMING_SNAKE_CASE invalide |
| Pas de `_` en fin | `"SITE_NAME_"` | Format SCREAMING_SNAKE_CASE invalide |
| Pas de minuscules | `"site_name"` | Format SCREAMING_SNAKE_CASE obligatoire |
| Non vide | `""` | Value ne peut pas être vide |

```
ERROR: Invalid @ExposedAs value "SITE__NAME" on SiteDTO#getName().
  Reason: Criterion names must follow strict SCREAMING_SNAKE_CASE format.
  Rules: no "__", no leading "_", no trailing "_".
  Expected pattern: [A-Z][A-Z0-9]*(_[A-Z][A-Z0-9]*)*
  Fix: Rename to "SITE_NAME".
```

---

## 6. Exemple Complet de Code Généré

### Source

```java
@Projection(from = Site.class)
public interface SiteDTO {
    
    @Projected(from = "name")
    @ExposedAs(value = "SITE_NAME", operators = {StandardOp.EQ, StandardOp.MATCHES})
    String getName();
    
    @Projected(from = "id")
    @ExposedAs(value = "SITE_ID", operators = {StandardOp.EQ, StandardOp.IN})
    Long getId();
    
    @Projected(from = "locality", as = "LOCALITY")
    LocalityDTO getLocality();
}

@Projection(from = Locality.class)
public interface LocalityDTO {
    
    @Projected(from = "name")
    @ExposedAs(value = "NAME", operators = {StandardOp.EQ})
    String getName();
}

@Projection(from = Connection.class)
@Exposure(value = "connections", namespace = "api", strategy = Strategy.WINDOWED)
public interface ConnectionDTO {
    
    @Projected(from = "sourceSite", as = "SOURCE_SITE")
    SiteDTO getSourceSite();
    
    @Projected(from = "targetSite", as = "TARGET_SITE")
    SiteDTO getTargetSite();
    
    @Projected(from = "label")
    @ExposedAs(value = "LABEL", operators = {StandardOp.EQ, StandardOp.MATCHES})
    String getLabel();
}
```

### Code généré pour `ConnectionDTO`

```java
// DirectMapping[] pour ConnectionDTO
DirectMapping[] directMappings = {
    new DirectMapping("sourceSite", "sourceSite", SiteDTO.class, Optional.empty(),
                      Optional.of("SOURCE_SITE"), false),
    new DirectMapping("targetSite", "targetSite", SiteDTO.class, Optional.empty(),
                      Optional.of("TARGET_SITE"), false),
    new DirectMapping("label", "label", String.class, Optional.empty(),
                      Optional.empty(), false)
};

// ExposedCriterion[] pour ConnectionDTO
ExposedCriterion[] criteria = {
    // Direct
    new ExposedCriterion("LABEL", "label",
        new String[]{"EQ", "MATCHES"}, true, false),
    
    // Composés depuis SiteDTO via SOURCE_SITE
    new ExposedCriterion("SOURCE_SITE__SITE_NAME", "sourceSite.name",
        new String[]{"EQ", "MATCHES"}, true, true),
    new ExposedCriterion("SOURCE_SITE__SITE_ID", "sourceSite.id",
        new String[]{"EQ", "IN"}, true, true),
    
    // Composés récursifs : SOURCE_SITE → LOCALITY
    new ExposedCriterion("SOURCE_SITE__LOCALITY__NAME", "sourceSite.locality.name",
        new String[]{"EQ"}, true, true),
    
    // Composés depuis SiteDTO via TARGET_SITE
    new ExposedCriterion("TARGET_SITE__SITE_NAME", "targetSite.name",
        new String[]{"EQ", "MATCHES"}, true, true),
    new ExposedCriterion("TARGET_SITE__SITE_ID", "targetSite.id",
        new String[]{"EQ", "IN"}, true, true),
    
    // Composés récursifs : TARGET_SITE → LOCALITY
    new ExposedCriterion("TARGET_SITE__LOCALITY__NAME", "targetSite.locality.name",
        new String[]{"EQ"}, true, true)
};

// ExposureMetadata pour ConnectionDTO (car @Exposure est présent)
ExposureMetadata exposure = new ExposureMetadata("connections", "api", "WINDOWED");

// ProjectionMetadata final
new ProjectionMetadata(
    Connection.class,
    directMappings,
    computedFields,    // les ComputedField habituels
    providers,         // les ComputationProvider habituels
    criteria,          // ← NOUVEAU
    exposure           // ← NOUVEAU
);
```

### Code généré pour `SiteDTO` (pas de `@Exposure`)

```java
DirectMapping[] directMappings = {
    new DirectMapping("name", "name", String.class, Optional.empty(),
                      Optional.empty(), false),
    new DirectMapping("id", "id", Long.class, Optional.empty(),
                      Optional.empty(), false),
    new DirectMapping("locality", "locality", LocalityDTO.class, Optional.empty(),
                      Optional.of("LOCALITY"), false)
};

ExposedCriterion[] criteria = {
    // Directs
    new ExposedCriterion("SITE_NAME", "name",
        new String[]{"EQ", "MATCHES"}, true, false),
    new ExposedCriterion("SITE_ID", "id",
        new String[]{"EQ", "IN"}, true, false),
    
    // Composés depuis LocalityDTO via LOCALITY
    new ExposedCriterion("LOCALITY__NAME", "locality.name",
        new String[]{"EQ"}, true, true)
};

new ProjectionMetadata(
    Site.class,
    directMappings,
    new ComputedField[]{},
    new ComputationProvider[]{},
    criteria,        // ← NOUVEAU
    null             // ← pas de @Exposure
);
```

---

## 7. Checklist d'Implémentation

- [ ] **Dépendance** : Mettre à jour vers `jpa-projection-metamodel:3.0.0` (ou `3.0.0-SNAPSHOT`)
- [ ] **`DirectMapping`** : Ajouter `logicalPrefix` et `cycleBreak` à toutes les instanciations
- [ ] **`ProjectionMetadata`** : Ajouter `exposedCriteria` et `exposure` à toutes les instanciations
- [ ] **`@ExposedAs` → `ExposedCriterion`** : Collecter les critères directs
- [ ] **Composed Criterion Inheritance** : Implémenter l'algorithme récursif (§4.1)
- [ ] **Cycle detection** : `Set<TypeMirror>` maintenu pendant le DFS
- [ ] **Prefix conflict detection** : Vérifier l'unicité des préfixes dans un même DTO
- [ ] **`@ExposedAs` sur non-scalaire** : Erreur de compilation
- [ ] **`@ExposedAs(value)` validation** : Pattern `[A-Z][A-Z0-9]*(_[A-Z][A-Z0-9]*)*`
- [ ] **`@Exposure` → `ExposureMetadata`** : Collecter et instancier
- [ ] **Réducteurs inline** : Parser `"path:REDUCER"` → `ReducerMapping(index, reducer)` + chemin propre
- [ ] **Tests** : Couvrir les scénarios directs, composés 1 niveau, composés 2+ niveaux, cycles, conflits