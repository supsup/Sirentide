# Sirentide gallery — real-browser renders

Captured by BrewShot in the test suite. Each diagram is audited so no drawn element escapes its canvas (the visual class the byte-pinned SVG goldens can't see).

## Pie

```
pie
"Reviews" : 40
"Builds" : 25
"Docs" : 20
"Design" : 15
```

![Pie](pie.png)

## Pie with a legend

```
pie legend
"Reviews" : 40
"Builds" : 25
"Docs" : 20
"Design" : 15
```

![Pie with a legend](pie-legend.png)

## Custom per-item colours

```
pie legend
"Ship" : 40 #22c55e
"WIP" : 25 #eab308
"Blocked" : 20 #ef4444
"Backlog" : 15 #64748b
```

![Custom per-item colours](pie-custom.png)

## Bar chart (signed axis)

```
xychart
"Mon" : 5
"Tue" : -3
"Wed" : 8
"Thu" : 6
```

![Bar chart (signed axis)](xychart.png)

## Timeline (proportional)

```
timeline
"Founded" : 2000
"Series A" : 2005
"Launch" : 2020
```

![Timeline (proportional)](timeline.png)

## Gantt

```
gantt
"Design" : 0-3
"Build" : 3-8
"Test" : 7-11
"Ship" : 11-13
```

![Gantt](gantt.png)

