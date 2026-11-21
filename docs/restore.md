# Migrar o Traccar (H2) para outra máquina

Base: `database.mv.db` (~1,1 GB) dentro do volume `traccar-sse_traccar-data`.

## 1. Máquina de origem: fazer o backup

```bash
docker compose stop traccar

mkdir -p ~/traccar-inspecao

sudo chown -R ubuntu:ubuntu ~/traccar-inspecao

docker run --rm \
  -v traccar-sse_traccar-data:/data:ro \
  -v ~/traccar-inspecao:/backup \
  alpine sh -c "cp -a /data/database.mv.db /backup/"

docker compose start traccar

cd ~/traccar-inspecao
sha256sum database.mv.db > database.mv.db.sha256
gzip -k database.mv.db
```

Descubra a tag exata da imagem e use a mesma no destino:

```bash
docker compose images traccar
```

## 2. Enviar para a VM

```bash
scp -i ~/.ssh/CHAVE ~/traccar-inspecao/database.mv.db.gz \
    ~/traccar-inspecao/database.mv.db.sha256 \
    USUARIO@IP_DA_VM:~/
```

Na VM:

```bash
gunzip -k database.mv.db.gz
sha256sum -c database.mv.db.sha256     # deve mostrar OK
```

## 3. Arquivo compose

Salve como `traccar-sse-image.yaml` na VM, em uma pasta qualquer:

```yaml
name: traccar-sse

services:
  traccar:
    image: traccar/traccar:TROCAR_PELA_TAG_EXATA
    container_name: traccar
    restart: unless-stopped
    ports:
      - "8082:8082"
      - "5000-5150:5000-5150"
    volumes:
      - traccar-data:/opt/traccar/data

volumes:
  traccar-data:
```

O `name: traccar-sse` faz o volume se chamar `traccar-sse_traccar-data`, igual ao original.

## 4. Criar o volume e restaurar a base

```bash
docker compose -f traccar-sse-image.yaml create

docker run --rm \
  -v traccar-sse_traccar-data:/data \
  -v ~:/origem:ro \
  alpine sh -c "cp /origem/database.mv.db /data/ && ls -lah /data"
```

## 5. Subir

```bash
docker compose -f traccar-sse-image.yaml up -d
docker compose -f traccar-sse-image.yaml logs -f traccar
```

Abra `http://IP_DA_VM:8082` e confira se os dispositivos e posições aparecem.

## Atenção

- Use a **mesma versão** do Traccar do original, senão o H2 pode recusar o arquivo.
- Em VM **ARM (A1)**, a imagem precisa ter build `arm64`. Teste com `docker pull traccar/traccar:TAG`.
- Com 1,1 GB de base, a VM de 1 GB de RAM (E2.1.Micro) é pouca. Prefira A1 com 6 GB.
- Libere as portas na Security List da OCI e no firewall da VM.
- Se você usa um `traccar.xml` próprio ou a pasta `media`, copie também.
