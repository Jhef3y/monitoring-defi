FROM node:22-alpine
WORKDIR /app

# instala dependências primeiro (cache de camada)
COPY package.json ./
RUN npm install --omit=dev

# código da aplicação
COPY server.js ./
COPY public ./public

EXPOSE 3000
USER node
CMD ["node", "server.js"]
