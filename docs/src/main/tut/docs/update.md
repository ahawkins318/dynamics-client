---
layout: docs
title: update
---

# update

The `update` command allows you to update entities based on an input json
file. You can also perform inserts via the "upsert" concept.

## Subcommands
* [entity](#entity): Update records as specified on the command line.
* [one-property](#one-property): Update a single property on a record from another attribute on the same record or a constant.


## entity

This command is designed to process a load ready file. It does not perform
lookups or other transformations. It can add/drop/rename attributes in the json
object. Each json object in the file should be in json streaming format, json
objects separated by newlines.

Let's say you downloaded reference and key information to your local
database. You might then run a sql command that mergesq the source data with the
key/reference data.

For example, I might need to update the name attribute of my entity and I use
the csv toolkit alot under linux, so:

```sh
sql2csv--db $DB update-new_myentity.sql  | csvjson --stream > new_myentity_name_update.json

```

This might produce:
```sh
{ "new_name": "some name", "new_myentityid": "f0ca7700-fb29-e811-a94f-000d3a324a3e" }
{ ... }
{ ... }
```

Then you can run:
```sh
$CLI update entity new_myentitys new_myentitys.json --pk new_myentityid
```

And the update is performed. If you have extra fields in the json that should
not be part of the update for any reason, you can use the `--drops col1,col2`
option to remove them.

If you add `--upsertpreventcreate false` (the default is true, to prevent
creating a new record) and the record does not exist, it will be inserted. While
this means you need to generate your own guids, this is not hard in practice and
allows you to load data with the same "primary key" as a source system, assuming
they use guids as well.

## one-property

Update a single property on a record. Batch is *not* used so increase the concurrency, e.g. `--concurrency 1000`.

The argument structure is `entity source target query`.

Other arguments include:
* `--constant`: Instead of another value on the same record, use this constant value instead. The value is parsed using `JSON.parse()`.
* `--skip-if-null <boolean>`: Default is true, so if the "source" value is null, the update step is skipped.

Examples:
*`dynamicsclient update one-property contact new_overriddenmodifiedon modifiedon '/contacts'`: Update the modified on property with another property, `new_overriddenmodifiedon` that was loaded specifically to overwrite modifiedon just like you can through the supported createdon override.
