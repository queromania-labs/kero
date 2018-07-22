package shop.queromania.labs.kero

import groovy.json.JsonBuilder
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.commons.lang3.StringUtils
import org.jsoup.Jsoup
import org.jsoup.select.Elements

import java.util.regex.Matcher

class Main {

    static main(args) {

        def asNumber = {
            it instanceof String ? Float.parseFloat(it.replaceAll(/,/, '.')) : it
        }

        def pricesById = new File('input.csv').collect { line ->
            def values = line.split(/,/)

            def id = values[0]
            def price = asNumber(values[1])
            def discountPrice = values.size() > 2 ? asNumber(values[2]) : ''

            [id, [price: price, discountPrice: discountPrice]]
        }.collectEntries()

        def products = pricesById.keySet().collect {
            def url = "http://demillus.vestemuitomelhor.com.br/?s=$it"
            println("Querying url = $url")
            def pageNode = Jsoup.connect(url).get()
            pageNode.select('div.list-item')?.collect {
                it.select('a[href*=pecas]')?.collect { it.attr('href') }
            }
        }.flatten().unique().findAll { url ->
            println("Getting info from ${url}")
            Jsoup.connect(url as String).get() != null
        }.collect { url ->
            def pageNode = Jsoup.connect(url as String).get()
            def contentNode = pageNode.select('div.entry-content')?.first()
            def descriptionNode = contentNode.select('div.descriptions')?.first()

            def description = descriptionNode?.select('p')?.first()?.text()?.trim()
            def title = contentNode?.select('h1.entry-title')?.first()?.text()?.trim()
            def excerpt = contentNode?.select('p.excerpt')?.first()?.text()?.trim()
            def originalCategory = pageNode?.select('ul#menu-principal')?.first()
                    ?.select('li.current-menu-parent > a')?.text()

            def normalized = [
                    title           : StringUtils.stripAccents(title).toLowerCase(),
                    description     : StringUtils.stripAccents(description).toLowerCase(),
                    excerpt         : StringUtils.stripAccents(excerpt).toLowerCase(),
                    originalCategory: StringUtils.stripAccents(originalCategory).toLowerCase()
            ]
            def uniqueUrl = normalized.title.replaceAll(/\s+/, '-')

            def id = descriptionNode.select('span').first().text().find(~'\\d{6}')
            [
                    id              : id,
                    uniqueUrl       : uniqueUrl,
                    price           : (pricesById.get(id) as Map)?.price,
                    discountPrice   : (pricesById.get(id) as Map)?.discountPrice,
                    url             : url,
                    title           : title,
                    excerpt         : excerpt,
                    description     : description,
                    sizes           : getAvailableSizes(description),
                    images          : contentNode?.select('div.images')?.first()
                            ?.select('a')?.collect { it.attr('href') },
                    colors          : getAvailableColors(contentNode?.select('div.cores')),
                    originalCategory: originalCategory,
                    normalized      : normalized,
                    displayOnStore  : 'SIM'
            ]
        }.collect {
            (it as Map) + [tags: getTags(it)]
        }.collect {
            (it as Map) + [categories: Category.getCategories(it).collect { it.toString() }]
        }

        def productsMap = products.collect { [it.uniqueUrl, it] }.collectEntries()
        def oldProductsMap = ProductsFromCsv.get()

        new File('products.json').write(new JsonBuilder(productsMap).toPrettyString(), 'UTF-8')
        new File('products_old.json').write(new JsonBuilder(oldProductsMap).toPrettyString(), 'UTF-8')

        def mergedProducts = oldProductsMap.collect { uniqueUrl, product ->
            uniqueUrl = uniqueUrl as String
            product = product as Map
            if (uniqueUrl in productsMap.keySet()) {
                def tags = (((productsMap[uniqueUrl] as Map).tags + product.tags) as List).unique()
                def categories = (((productsMap[uniqueUrl] as Map).categories + product.categories) as List).unique()
                def description = product.description
                def seo = product.seo
                productsMap[uniqueUrl] + [tags: tags, categories: categories, description: description, seo: seo]
            } else {
                println('=======================================================')
                product + [displayOnStore: 'NÃO']
            }
        }
        mergedProducts += (productsMap.values() as List).findAll { !((it as Map).uniqueUrl in oldProductsMap.keySet()) }

        new File('products_merged.json').write(new JsonBuilder(productsMap).toPrettyString(), 'UTF-8')

        /*products.collect { p ->
            p.images.eachWithIndex { imageUrl, i ->
                def fileName = "${(p.normalized as Map).title}-$i"
                getImage(imageUrl as String, fileName)
            }
        }*/

//        exportToCsv(products)
        exportToCsv(mergedProducts)
    }

    static List productToCsv(Map product) {
        GroovyCollections.combinations(product.sizes, product.colors).collect {
            def item = it as List
            [
                    product.uniqueUrl,
                    product.title,
                    "\"${product.categories.join(',')}\"",
                    'Tamanho',
                    item[0],
                    'Cor',
                    (item[1] as String)?.toLowerCase() ?: '',
                    product.price ?: '',
                    product.discountPrice ?: '',
                    // Weight, Dimensions, Stock
                    0, // peso TODO
                    0, // altura TODO
                    0, // largura TODO
                    0, // comprimento
                    '',
                    // SKU
                    "\"${product.id}\"",
                    '',
                    product.displayOnStore,
                    'NÃO',
                    "\"${formatDescription(product.description as String)}\"",
                    // SEO
                    "\"${(product.tags as List).join(',')}\"",
                    product.title,
                    "\"${product.seo?.description}\""
            ].join(',')
        }
    }

    static List<String> getAvailableSizes(String description) {
        if (!description) return []

        description = description.toUpperCase()

        def sizeList = { Matcher matcher ->
            println(matcher.pattern())
            println(matcher[0])
            def lower = Integer.parseInt(matcher.group(1))
            def higher = Integer.parseInt(matcher.group(2))
            return (lower..higher).findAll { it % 2 == 0 }.collect { "$it".toString() }
        }

        def matcher = (description =~ /UN \(VESTE (\w+) AO? (\w+)\)/)
        if (matcher.size()) {
            println(matcher.pattern())
            println(matcher[0])
            def min = matcher.group(1)
            def max = matcher.group(2)
            return ["UN ($min - $max)"]
        }

        matcher = (description =~ /TAM\.: (\d+) A (\d+)/)
        if (matcher.size()) {
            return sizeList(matcher)
        }

        matcher = (description =~ /TAMANHOS: (\d+) A (\d+)/)
        if (matcher.size()) {
            return sizeList(matcher)
        }

        matcher = (description =~ /TAM\.:(( \w+)+)/)
        if (matcher.size()) {
            println(matcher.pattern())
            println(matcher[0])
            return matcher.group(1).trim().split(/\s+/)
        }

        matcher = (description =~ /TAMANHOS:(( \w+)+)/)
        if (matcher.size()) {
            println(matcher.pattern())
            println(matcher[0])
            return matcher.group(1).trim().split(/\s+/)
        }

        ['']
    }

    static List<String> getAvailableColors(Elements colorsNode) {
        if (!colorsNode.size()) return []
        colorsNode.first().select('a.field-color').collect { it.text() }
    }

    static List getTags(Map product) {
        [
                (product.normalized.title as String).split(/\s+/),
                (product.normalized.originalCategory as String).split(/\s+/)
        ].flatten().findAll { it && (it =~ /\w+/).size() }
    }

    static void exportToCsv(List products) {
        def file = products.collect {
            productToCsv(it as Map)
        }.flatten().join('\n')
        def header = 'Identificador URL,Nome,Categorias,' +
                'Nome da variação 1,Valor da variação 1,Nome da variação 2,Valor da variação 2,' +
                'Preço,Preço promocional,' +
                'Peso,Altura,Largura,Comprimento,' +
                'Estoque,SKU,Código de barras,' +
                'Exibir na loja,Frete gratis,' +
                'Descrição,Tags,Título para SEO,Descrição para SEO\n'
        new File('output.csv').write(header + file)
    }

    static String formatDescription(String description) {
        description = StringEscapeUtils.escapeHtml4(description)
        def attentionTitle = StringEscapeUtils.escapeHtml4('ATENÇÃO')
        def attentionNote = StringEscapeUtils.escapeHtml4('A confecção da DeMillus é pequena. Consulte a tabela ' +
                'abaixo para saber suas medidas e evitar trocas.')
        "<p>$description</p>" +
                "<p>&nbsp;</p>" +
                "<p><span style=\"color:#FF0000;\"><strong>$attentionTitle</strong></span></p>" +
                "<p><strong>$attentionNote</strong><p>" +
                "<p>&nbsp;</p>"
        // TODO add more key-specific info: tables, images and such
    }

    static void getImage(String url, String name) {

        def ACCEPT = '*/*'
        def CONNECTION = 'keep-alive'
        def TOKEN = 'd7b540383018111a90b686758154559b01506215457' // TODO how to get this token?
        def USER_AGENT = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
                'Chrome/57.0.2987.110 Safari/537.36' // FIXME is this required?
        def COOKIE = "__cfduid=$TOKEN".toString()

        def format = 'jpg'
        def file = new File("images/$name.$format")
        file.withOutputStream { outputStream ->
            println url
            def connection = new URL(url).openConnection()
            connection.with {
                addRequestProperty('Accept', ACCEPT)
                addRequestProperty('Connection', CONNECTION)
                addRequestProperty('Cookie', COOKIE)
                addRequestProperty('User-Agent', USER_AGENT)
                outputStream << it.inputStream
            }
            outputStream.close()
        }
    }

}